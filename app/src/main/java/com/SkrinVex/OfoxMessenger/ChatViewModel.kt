package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.regex.Pattern

data class Message(
    val id: String,
    val senderId: String,
    val content: String,
    val timestamp: Long,
    val status: String
)

data class ChatState(
    val messages: List<Message> = emptyList(),
    val messageText: String = "",
    val isSending: Boolean = false,
    val error: String? = null
)

class ChatViewModel(
    private val currentUserId: String,
    private val friendUid: String
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()
    private var messagesListener: ValueEventListener? = null

    init {
        setupMessagesListener()
    }

    private fun getChatId(): String {
        return if (currentUserId < friendUid) {
            "${currentUserId}_$friendUid"
        } else {
            "${friendUid}_$currentUserId"
        }
    }

    fun updateMessageText(text: String) {
        _state.value = _state.value.copy(messageText = text)
    }

    fun sendMessage() {
        viewModelScope.launch {
            if (_state.value.messageText.isBlank()) return@launch
            _state.value = _state.value.copy(isSending = true)
            try {
                val messageId = UUID.randomUUID().toString()
                val message = mapOf(
                    "id" to messageId,
                    "senderId" to currentUserId,
                    "content" to _state.value.messageText,
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "sent"
                )

                val chatId = getChatId()
                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId")
                    .setValue(message)
                    .await()

                // Обновляем статус на "delivered"
                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId/status")
                    .setValue("delivered")
                    .await()

                // Отправляем уведомление о том что вас упомянули
                val mentions = extractMentions(_state.value.messageText)
                for (mention in mentions) {
                    val uid = getUidByUsername(mention)
                    if (uid != null && uid != currentUserId) {
                        val notificationId = UUID.randomUUID().toString()
                        FirebaseDatabase.getInstance()
                            .getReference("users/$uid/notifications/$notificationId")
                            .setValue(
                                mapOf(
                                    "type" to "mention",
                                    "from_uid" to currentUserId,
                                    "message_id" to messageId,
                                    "timestamp" to System.currentTimeMillis(),
                                    "message" to "$mention, вас упомянули в сообщении"
                                )
                            ).await()
                    }
                }

                // Отправляем уведомление о новом сообщении
                val notificationId = UUID.randomUUID().toString()
                FirebaseDatabase.getInstance()
                    .getReference("users/$friendUid/notifications/$notificationId")
                    .setValue(mapOf(
                        "type" to "new_message",
                        "from_uid" to currentUserId,
                        "message_id" to messageId,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "Новое сообщение: ${_state.value.messageText.take(30)}..."
                    )).await()

                _state.value = _state.value.copy(
                    messageText = "",
                    isSending = false
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error sending message: ${e.message}", e)
                _state.value = _state.value.copy(
                    isSending = false,
                    error = "Ошибка отправки: ${e.message}"
                )
            }
        }
    }

    fun extractMentions(text: String): List<String> {
        val pattern = Pattern.compile("@[a-zA-Z0-9_]+")
        val matcher = pattern.matcher(text)
        val mentions = mutableListOf<String>()
        while (matcher.find()) {
            mentions.add(matcher.group())
        }
        return mentions
    }

    suspend fun getUidByUsername(username: String): String? {
        return try {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("users")
                .orderByChild("username")
                .equalTo(username)
                .get()
                .await()
            snapshot.children.firstOrNull()?.key
        } catch (e: Exception) {
            null
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            try {
                val chatId = getChatId()
                // Удаляем сообщение
                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId")
                    .removeValue()
                    .await()

                // Находим и удаляем связанное уведомление
                val notificationsSnapshot = FirebaseDatabase.getInstance()
                    .getReference("users/$friendUid/notifications")
                    .get()
                    .await()
                notificationsSnapshot.children.forEach { snapshot ->
                    val notificationData = snapshot.value as? Map<String, Any>
                    if (notificationData?.get("type") == "new_message" && notificationData["message_id"] == messageId) {
                        snapshot.ref.removeValue().await()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error deleting message: ${e.message}", e)
                _state.value = _state.value.copy(error = "Ошибка удаления: ${e.message}")
            }
        }
    }

    private fun setupMessagesListener() {
        messagesListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("chats/${getChatId()}/messages")
                .removeEventListener(it)
        }

        messagesListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val messages = mutableListOf<Message>()
                snapshot.children.forEach { child ->
                    val messageData = child.value as? Map<String, Any>
                    if (messageData != null) {
                        val message = Message(
                            id = messageData["id"] as? String ?: "",
                            senderId = messageData["senderId"] as? String ?: "",
                            content = messageData["content"] as? String ?: "",
                            timestamp = (messageData["timestamp"] as? Long) ?: 0L,
                            status = messageData["status"] as? String ?: "sent"
                        )
                        messages.add(message)

                        // Обновляем статус на "read" для чужих сообщений
                        if (message.senderId != currentUserId && message.status != "read") {
                            FirebaseDatabase.getInstance()
                                .getReference("chats/${getChatId()}/messages/${message.id}/status")
                                .setValue("read")
                        }
                    }
                }
                _state.value = _state.value.copy(messages = messages.sortedBy { it.timestamp })
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatViewModel", "Messages listener error: ${error.message}")
                _state.value = _state.value.copy(error = "Ошибка загрузки: ${error.message}")
            }
        }

        FirebaseDatabase.getInstance()
            .getReference("chats/${getChatId()}/messages")
            .addValueEventListener(messagesListener!!)
    }

    override fun onCleared() {
        messagesListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("chats/${getChatId()}/messages")
                .removeEventListener(it)
        }
        super.onCleared()
    }
}

class ChatViewModelFactory(
    private val currentUserId: String,
    private val friendUid: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(currentUserId, friendUid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}