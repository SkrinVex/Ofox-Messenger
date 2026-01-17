package com.SkrinVex.OfoxMessenger

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.app.TaskStackBuilder
import androidx.core.graphics.drawable.toBitmap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import coil.ImageLoader
import coil.request.ImageRequest
import coil.transform.CircleCropTransformation
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PushNotificationService : FirebaseMessagingService() {

    private var notificationsListener: ValueEventListener? = null
    private val processedNotifications = ConcurrentHashMap<String, Long>() // Store notification ID and timestamp

    override fun onCreate() {
        super.onCreate()
        updateFcmToken()
        startNotificationsListener()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val type = data["type"] ?: return
        val fromUid = data["from_uid"]
        val chatId = data["chat_id"]
        val notificationId = data["notification_id"] ?: return

        val isGroup = type == "group_message"
        val buttonText = data["button_text"]
        val buttonUrl = data["button_url"]
        val messageId = data["message_id"]
        val postId = data["post_id"]
        val commentId = data["comment_id"]
        val message = data["message"]?.trim() ?: "Новое уведомление в Ofox Messenger!"

        // Mark notification as processed
        processedNotifications[notificationId] = System.currentTimeMillis()

        CoroutineScope(Dispatchers.IO).launch {
            val (title, content, photoUrl) = if (isGroup) {
                val groupName = data["group_name"] ?: "Групповой чат"
                val groupPhotoUrl = data["group_photo"] ?: "https://api.skrinvex.su/default_group_photo.png"
                val senderName = data["sender_name"] ?: "Участник"
                Triple(groupName, "$senderName: $message", groupPhotoUrl)
            } else {
                val profile = loadProfile(fromUid)
                val nickname = profile?.nickname ?: "Пользователь"
                val photo = profile?.profile_photo ?: "https://api.skrinvex.su/default_profile_photo.png"
                Triple(nickname, message, photo)
            }

            val profileBitmap = loadProfilePhoto(photoUrl)

            val unreadMessages = if (type == "chat_message" && chatId != null) {
                getUnreadMessages(chatId, fromUid)
            } else {
                emptyList()
            }

            withContext(Dispatchers.Main) {
                showNotification(
                    title = title,
                    message = message,
                    profilePhotoBitmap = profileBitmap,
                    fromUid = fromUid,
                    notificationId = notificationId,
                    type = type,
                    chatId = chatId,
                    messageId = messageId,
                    postId = postId,
                    commentId = commentId,
                    buttonText = buttonText,
                    buttonUrl = buttonUrl,
                    unreadMessages = unreadMessages,
                    groupName = if (isGroup) data["group_name"] else null,
                    groupPhoto = if (isGroup) data["group_photo"] else null,
                    profilePhotoUrl = photoUrl
                )
            }
        }
    }

    private fun updateFcmToken() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid.isNullOrEmpty()) {
            FirebaseAuth.getInstance().addAuthStateListener { auth ->
                auth.currentUser?.uid?.let {
                    FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                        saveToken(it, token)
                    }
                }
            }
        } else {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                saveToken(uid, token)
            }
        }
    }

    private fun saveToken(uid: String, token: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseDatabase.getInstance()
                    .getReference("users/$uid/fcm_token")
                    .setValue(token)
                    .await()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        saveToken(uid, token)
    }

    private fun startNotificationsListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference("users/$uid/notifications")

        notificationsListener?.let { ref.removeEventListener(it) }

        notificationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.forEach { child ->
                    val data = child.value as? Map<String, Any?> ?: return
                    val notificationId = child.key ?: return
                    val timestamp = (data["timestamp"] as? Long) ?: return
                    val type = data["type"] as? String ?: return

                    // Skip if already processed or recently processed (within 5 seconds)
                    val processedTime = processedNotifications[notificationId]
                    if (processedTime != null && System.currentTimeMillis() - processedTime < 5000) {
                        return
                    }

                    // Skip chat_message notifications to avoid duplicates with FCM
                    if (type == "chat_message") {
                        return
                    }

                    val fromUid = data["from_uid"] as? String
                    val buttonText = data["button_text"] as? String
                    val buttonUrl = data["button_url"] as? String
                    val chatId = data["chat_id"] as? String
                    val messageId = data["message_id"] as? String
                    val postId = data["post_id"] as? String
                    val commentId = data["comment_id"] as? String
                    val message = (data["message"] as? String)?.trim() ?: "Новое уведомление в Ofox Messenger!"

                    CoroutineScope(Dispatchers.IO).launch {
                        val profile = loadProfile(fromUid)
                        val nickname = profile?.nickname ?: "Пользователь"
                        val profilePhoto = profile?.profile_photo ?: "https://api.skrinvex.su/default_profile_photo.png"
                        val profileBitmap = loadProfilePhoto(profilePhoto)

                        val title = when (type) {
                            "mention" -> "$nickname упомянул вас"
                            "friend_request" -> "$nickname отправил заявку в друзья"
                            "friend_added" -> "$nickname добавил вас в друзья"
                            "post_mention" -> "$nickname упомянул вас в посте"
                            "comment_mention" -> "$nickname упомянул вас в комментарии"
                            else -> "Новое уведомление"
                        }

                        withContext(Dispatchers.Main) {
                            showNotification(
                                title = title,
                                message = message,
                                profilePhotoBitmap = profileBitmap,
                                fromUid = fromUid,
                                notificationId = notificationId,
                                type = type,
                                chatId = chatId,
                                messageId = messageId,
                                postId = postId,
                                commentId = commentId,
                                buttonText = buttonText,
                                buttonUrl = buttonUrl,
                                unreadMessages = emptyList()
                            )
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {
                // Log error if needed
            }
        }

        ref.addValueEventListener(notificationsListener!!)
    }

    private suspend fun loadProfile(uid: String?): ProfileCheckResponse? {
        if (uid.isNullOrEmpty()) return null
        return try {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("users/$uid")
                .get()
                .await()
            val data = snapshot.value as? Map<String, Any>
            data?.let {
                ProfileCheckResponse(
                    nickname = it["nickname"] as? String,
                    profile_photo = it["profile_photo"] as? String
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun loadProfilePhoto(photoPath: String?): Bitmap? {
        if (photoPath.isNullOrEmpty()) return null
        return try {
            val loader = ImageLoader(this)
            val request = ImageRequest.Builder(this)
                .data(photoPath)
                .allowHardware(false)
                .transformations(CircleCropTransformation())
                .build()
            loader.execute(request).drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun getUnreadMessages(chatId: String, fromUid: String?): List<Message> {
        return try {
            val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return emptyList()
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("chats/$chatId/messages")
                .get()
                .await()
            val messages = mutableListOf<Message>()
            snapshot.children.forEach { child ->
                val messageData = child.value as? Map<String, Any>
                if (messageData != null) {
                    val status = messageData["status"] as? String
                    val senderId = messageData["senderId"] as? String
                    if (status != "read" && senderId == fromUid) {
                        messages.add(
                            Message(
                                id = messageData["id"] as? String ?: "",
                                senderId = senderId ?: "",
                                content = (messageData["content"] as? String)?.trim() ?: "",
                                timestamp = (messageData["timestamp"] as? Long) ?: 0L,
                                status = status ?: "sent"
                            )
                        )
                    }
                }
            }
            messages.sortedBy { it.timestamp }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun showNotification(
        title: String,
        message: String,
        profilePhotoBitmap: Bitmap?,
        fromUid: String?,
        notificationId: String?,
        type: String,
        chatId: String? = null,
        messageId: String? = null,
        postId: String? = null,
        commentId: String? = null,
        buttonText: String? = null,
        buttonUrl: String? = null,
        profilePhotoUrl: String? = null,
        unreadMessages: List<Message> = emptyList(),
        groupName: String? = null,
        groupPhoto: String? = null
    ) {
        val channelId = "ofox_messenger_notifications"
        val notificationIdInt = when (type) {
            "group_message" -> chatId?.hashCode() ?: notificationId?.hashCode() ?: UUID.randomUUID().hashCode()
            else -> notificationId?.hashCode() ?: UUID.randomUUID().hashCode()
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "Ofox Messenger Notifications",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления от Ofox Messenger"
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        // Intent for tapping the notification
        val stackBuilder = TaskStackBuilder.create(this)

        // Add MainPageActivity to the back stack
        val mainIntent = Intent(this, MainPageActivity::class.java).apply {
            putExtra("uid", FirebaseAuth.getInstance().currentUser?.uid)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        stackBuilder.addNextIntentWithParentStack(mainIntent)

        // Create the specific intent for the notification's target screen
        val targetIntent = when (type) {
            "chat_message" -> Intent(this, ChatActivity::class.java).apply {
                putExtra("friend_uid", fromUid)
                putExtra("friend_name", title)
                putExtra("friend_photo", profilePhotoUrl ?: "")
                putExtra("notificationId", notificationId)
            }
            "group_message" -> Intent(this, GroupChatActivity::class.java).apply {
                putExtra("group_id", chatId)
                putExtra("group_name", groupName ?: title)
                putExtra("group_photo", groupPhoto)
                putExtra("notificationId", notificationId)
            }
            "post_mention", "comment_mention" -> Intent(this, PostsActivity::class.java).apply {
                putExtra("post_id", postId)
                putExtra("comment_id", commentId)
                putExtra("notificationId", notificationId)
            }
            "friend_request", "friend_added" -> Intent(this, ProfileViewActivity::class.java).apply {
                putExtra("uid", fromUid)
                putExtra("notificationId", notificationId)
            }
            else -> null // For other types, only the main intent will be in the stack
        }

        // Add the target intent to the stack if it exists
        if (targetIntent != null) {
            stackBuilder.addNextIntent(targetIntent)
        }

        val pendingIntent = stackBuilder.getPendingIntent(
            notificationIdInt,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Main notification
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6B35.toInt())
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setGroup(
                if (type == "group_message") "group_$chatId" else "chat_$chatId"
            )

        if (type == "group_message") {
            builder.setContentTitle(title) // вместо nickname
            builder.setContentText("У вас новые сообщения в группе")
        }

        profilePhotoBitmap?.let { builder.setLargeIcon(it) }

        // Add action buttons for chat_message
        if (type == "chat_message") { // <-- убрали group_message отсюда
            // MARK AS READ
            val markReadIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "MARK_AS_READ"
                putExtra("chat_id", chatId)
                putExtra("message_id", messageId)
                putExtra("notification_id", notificationId)
                putExtra("notification_id_int", notificationIdInt)
                putExtra("is_group", false) // всегда false для личных чатов
            }
            val markReadPending = PendingIntent.getBroadcast(
                this,
                notificationIdInt + 1,
                markReadIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_check, "Прочитано", markReadPending)

            // Reply action (только для обычных чатов)
            val replyLabel = "Введите ответ"
            val remoteInput = RemoteInput.Builder("key_text_reply")
                .setLabel(replyLabel)
                .build()

            val replyIntent = Intent(this, NotificationActionReceiver::class.java).apply {
                action = "REPLY"
                putExtra("notification_id", notificationId)
                putExtra("notification_id_int", notificationIdInt)
                putExtra("friend_uid", fromUid) // только личные чаты
            }

            val replyPendingIntent = PendingIntent.getBroadcast(
                this,
                notificationIdInt + 2,
                replyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE
                } else {
                    0
                }
            )

            val replyAction = NotificationCompat.Action.Builder(
                R.drawable.ic_reply,
                "Ответить",
                replyPendingIntent
            ).addRemoteInput(remoteInput).build()

            builder.addAction(replyAction)
        }

        // Add custom action button if provided
        if (buttonText != null && buttonUrl != null) {
            val actionIntent = Intent(Intent.ACTION_VIEW, Uri.parse(buttonUrl))
            val actionPendingIntent = PendingIntent.getActivity(
                this,
                notificationIdInt + 3,
                actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(R.drawable.ic_check, buttonText, actionPendingIntent)
        }

        // Show main notification
        with(NotificationManagerCompat.from(this)) {
            if (areNotificationsEnabled()) {
                notify(notificationIdInt, builder.build())
            }
        }

        // Show summary notification for multiple unread messages
        if (type == "chat_message" && unreadMessages.size > 1) {
            val summaryNotificationId = "summary_$chatId".hashCode()
            val summaryBuilder = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setColor(0xFFFF6B35.toInt())
                .setContentTitle("Новые сообщения от $title")
                .setContentText("У вас ${unreadMessages.size} непрочитанных сообщений")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setGroup("chat_$chatId")
                .setGroupSummary(true)

            profilePhotoBitmap?.let { summaryBuilder.setLargeIcon(it) }

            val inboxStyle = NotificationCompat.InboxStyle()
            unreadMessages.take(5).forEach { msg ->
                inboxStyle.addLine(msg.content.take(50) + if (msg.content.length > 50) "..." else "")
            }
            if (unreadMessages.size > 5) {
                inboxStyle.setSummaryText("И еще ${unreadMessages.size - 5} сообщений")
            }
            summaryBuilder.setStyle(inboxStyle)

            with(NotificationManagerCompat.from(this)) {
                if (areNotificationsEnabled()) {
                    notify(summaryNotificationId, summaryBuilder.build())
                }
            }
        }
    }
}
