package com.SkrinVex.OfoxMessenger

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.RemoteInput
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.network.ApiService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class NotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        val currentUserId = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val notificationId = intent.getStringExtra("notification_id")
        val notificationIdInt = intent.getIntExtra("notification_id_int", 0)
        val isGroup = intent.getBooleanExtra("is_group", false)

        if (action == "MARK_AS_READ") {
            val chatId = intent.getStringExtra("chat_id")
            val messageId = intent.getStringExtra("message_id")

            if (chatId != null && messageId != null) {
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isGroup) {
                            // For groups, update the read receipt for the current user
                            FirebaseDatabase.getInstance()
                                .getReference("group_chats/$chatId/read_receipts/$messageId/$currentUserId")
                                .setValue(true)
                                .await()
                        } else {
                            // For private chats, update the message status
                            FirebaseDatabase.getInstance()
                                .getReference("chats/$chatId/messages/$messageId/status")
                                .setValue("read")
                                .await()
                        }

                        // Remove notification from database
                        if (notificationId != null) {
                            FirebaseDatabase.getInstance()
                                .getReference("users/$currentUserId/notifications/$notificationId")
                                .removeValue()
                                .await()
                        }

                        // Cancel the notification from the system tray
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(notificationIdInt)
                    } catch (e: Exception) {
                        Log.e("NotificationReceiver", "Error marking as read: ${e.message}", e)
                    }
                }
            }
        } else if (action == "REPLY") {
            val replyText = RemoteInput.getResultsFromIntent(intent)
                ?.getCharSequence("key_text_reply")
                ?.toString()
                ?.trim()

            if (replyText != null) {
                val messageId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                val message = mapOf(
                    "id" to messageId,
                    "senderId" to currentUserId,
                    "content" to replyText,
                    "timestamp" to timestamp,
                    "status" to "sent"
                )

                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        if (isGroup) {
                            val groupId = intent.getStringExtra("group_id") ?: return@launch
                            // Send message to group chat
                            FirebaseDatabase.getInstance()
                                .getReference("group_chats/$groupId/messages/$messageId")
                                .setValue(message)
                                .await()

                            // Get group members to send notifications
                            val groupMetaSnapshot = FirebaseDatabase.getInstance().getReference("group_chats/$groupId/meta").get().await()
                            val groupData = groupMetaSnapshot.value as? Map<String, Any>
                            val members = (groupData?.get("members") as? Map<String, Any>)?.keys ?: emptySet()
                            val groupName = groupData?.get("name") as? String ?: "Групповой чат"
                            val groupPhoto = groupData?.get("photo") as? String

                            // Send notification to each member
                            val api = ApiService.create()
                            for (memberUid in members) {
                                if (memberUid != currentUserId) {
                                    api.sendGroupNotification(
                                        type = "group_message",
                                        fromUid = currentUserId,
                                        toUid = memberUid,
                                        message = replyText,
                                        chatId = groupId,
                                        messageId = messageId,
                                        groupName = groupName,
                                        groupPhoto = groupPhoto
                                    )
                                }
                            }

                        } else {
                            val friendUid = intent.getStringExtra("friend_uid") ?: return@launch
                            val chatId = if (currentUserId < friendUid) "${currentUserId}_${friendUid}" else "${friendUid}_${currentUserId}"

                            // Send message to private chat
                            FirebaseDatabase.getInstance()
                                .getReference("chats/$chatId/messages/$messageId")
                                .setValue(message)
                                .await()

                            // Update status to delivered
                            FirebaseDatabase.getInstance()
                                .getReference("chats/$chatId/messages/$messageId/status")
                                .setValue("delivered")
                                .await()

                            // Send push notification
                            val api = ApiService.create()
                            api.sendNotification(
                                type = "chat_message",
                                fromUid = currentUserId,
                                toUid = friendUid,
                                message = replyText,
                                chatId = chatId,
                                messageId = messageId
                            )

                            // Add notification to friend's database
                            val newNotificationId = UUID.randomUUID().toString()
                            FirebaseDatabase.getInstance()
                                .getReference("users/$friendUid/notifications/$newNotificationId")
                                .setValue(
                                    mapOf(
                                        "type" to "chat_message",
                                        "from_uid" to currentUserId,
                                        "chat_id" to chatId,
                                        "message_id" to messageId,
                                        "timestamp" to timestamp,
                                        "message" to replyText.take(30) + if (replyText.length > 30) "..." else ""
                                    )
                                ).await()
                        }

                        // Remove original notification from database
                        if (notificationId != null) {
                            FirebaseDatabase.getInstance()
                                .getReference("users/$currentUserId/notifications/$notificationId")
                                .removeValue()
                                .await()
                        }

                        // Cancel the original notification from the system tray
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(notificationIdInt)

                    } catch (e: Exception) {
                        Log.e("NotificationReceiver", "Error sending reply: ${e.message}", e)
                    }
                }
            }
        }
    }
}
