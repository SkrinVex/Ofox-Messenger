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
import androidx.core.graphics.drawable.toBitmap
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.tasks.await
import coil.ImageLoader
import coil.request.ImageRequest
import java.util.UUID

class PushNotificationService : FirebaseMessagingService() {

    private var notificationsListener: ValueEventListener? = null

    override fun onCreate() {
        super.onCreate()
        // При старте сервиса обновляем токен
        updateFcmToken()
        // Запускаем слушатель базы
        startNotificationsListener()
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        val data = remoteMessage.data
        val fromUid = data["from_uid"]
        val notificationId = data["notification_id"]
        val buttonText = data["button_text"]
        val buttonUrl = data["button_url"]

        CoroutineScope(Dispatchers.IO).launch {
            val profile = loadProfile(fromUid)
            val nickname = profile?.nickname ?: "Пользователь"

            val title = "Привет, $nickname!"
            val message = data["message"] ?: "У вас новое уведомление в Ofox Messenger!"

            val profileBitmap = loadProfilePhoto(profile?.profile_photo)

            withContext(Dispatchers.Main) {
                showNotification(
                    title,
                    message,
                    profileBitmap,
                    fromUid,
                    notificationId,
                    buttonText,
                    buttonUrl
                )
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        saveToken(token)
    }

    private fun updateFcmToken() {
        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
            saveToken(token)
        }
    }

    private fun saveToken(token: String) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                FirebaseDatabase.getInstance()
                    .getReference("users/$uid/fcm_token")
                    .setValue(token)
                    .await()
            } catch (_: Exception) {
            }
        }
    }

    private fun startNotificationsListener() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val ref = FirebaseDatabase.getInstance().getReference("users/$uid/notifications")

        notificationsListener?.let { ref.removeEventListener(it) }

        notificationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                snapshot.children.lastOrNull()?.let { child ->
                    val data = child.value as? Map<String, Any?> ?: return
                    val fromUid = data["from_uid"] as? String
                    val notificationId = child.key
                    val buttonText = data["button_text"] as? String
                    val buttonUrl = data["button_url"] as? String

                    CoroutineScope(Dispatchers.IO).launch {
                        val profile = loadProfile(fromUid)
                        val nickname = profile?.nickname ?: "Пользователь"

                        val title = data["title"] as? String ?: "Привет, $nickname!"
                        val message = data["message"] as? String
                            ?: "У вас новое уведомление в Ofox Messenger!"

                        val profileBitmap = loadProfilePhoto(profile?.profile_photo)

                        withContext(Dispatchers.Main) {
                            showNotification(
                                title,
                                message,
                                profileBitmap,
                                fromUid,
                                notificationId,
                                buttonText,
                                buttonUrl
                            )
                        }
                    }
                }
            }

            override fun onCancelled(error: DatabaseError) {}
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
                .data("https://api.skrinvex.su$photoPath")
                .allowHardware(false)
                .build()
            loader.execute(request).drawable?.toBitmap()
        } catch (e: Exception) {
            null
        }
    }

    private fun showNotification(
        title: String,
        message: String,
        profilePhotoBitmap: Bitmap?,
        fromUid: String?,
        notificationId: String?,
        buttonText: String? = null,
        buttonUrl: String? = null
    ) {
        val channelId = "ofox_messenger_notifications"
        val notificationIdInt = UUID.randomUUID().hashCode()

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

        val intent = Intent(this, ProfileViewActivity::class.java).apply {
            putExtra("uid", FirebaseAuth.getInstance().currentUser?.uid)
            putExtra("friend_uid", fromUid)
            putExtra("notificationId", notificationId)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            notificationIdInt,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(0xFFFF6B35.toInt())
            .setContentTitle(title)
            .setContentText(message)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)

        profilePhotoBitmap?.let { builder.setLargeIcon(it) }

        // Добавляем кнопку, если данные есть
        if (!buttonText.isNullOrEmpty() && !buttonUrl.isNullOrEmpty()) {
            val linkIntent = Intent(Intent.ACTION_VIEW, Uri.parse(buttonUrl))
            val linkPendingIntent = PendingIntent.getActivity(
                this,
                0,
                linkIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            builder.addAction(
                R.drawable.ic_open_link,
                buttonText,
                linkPendingIntent
            )
        }

        with(NotificationManagerCompat.from(this)) {
            if (areNotificationsEnabled()) {
                notify(notificationIdInt, builder.build())
            }
        }
    }
}