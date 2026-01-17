package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.database.*
import com.SkrinVex.OfoxMessenger.network.ApiService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID
import java.util.regex.Pattern

// Базовый ViewModel для общих чатов
abstract class BaseChatViewModel(
    protected val currentUserId: String
) : ViewModel() {

    protected abstract val chatRef: DatabaseReference

    // сделаем _state protected чтобы наследники могли безопасно читать/использовать
    protected val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var lastLoadedTimestamp: Long? = null
    private val pageSize = 20
    protected var currentMaxTimestamp: Long = 0L
    private var newMessagesListener: ChildEventListener? = null

    override fun onCleared() {
        super.onCleared()
        newMessagesListener?.let { chatRef.removeEventListener(it) }
    }

    abstract fun updateTyping(isTyping: Boolean)

    fun updateMessageText(text: String) {
        _state.value = _state.value.copy(messageText = text)
        updateTyping(text.isNotBlank())
    }

    abstract fun clearTyping()

    fun onReply(message: Message) {
        _state.value = _state.value.copy(replyingTo = message)
    }

    fun cancelReply() {
        _state.value = _state.value.copy(replyingTo = null)
    }

    fun sendMessage() {
        viewModelScope.launch {
            val textToSend = _state.value.messageText.trim()
            if (textToSend.isBlank()) return@launch
            _state.value = _state.value.copy(isSending = true)

            try {
                val messageId = UUID.randomUUID().toString()
                val timestamp = System.currentTimeMillis()
                val replyingTo = _state.value.replyingTo

                val tempMessage = Message(
                    id = messageId,
                    senderId = currentUserId,
                    content = textToSend,
                    timestamp = timestamp,
                    status = "sent",
                    replyToMessageId = replyingTo?.id,
                    replyToMessageContent = replyingTo?.content
                )
                _state.value = _state.value.copy(
                    messages = (_state.value.messages + tempMessage).sortedBy { it.timestamp },
                    messageText = "",
                    isSending = false,
                    error = null,
                    replyingTo = null // Clear reply state after sending
                )

                clearTyping()

                val message = mutableMapOf<String, Any>(
                    "id" to messageId,
                    "senderId" to currentUserId,
                    "content" to textToSend,
                    "timestamp" to timestamp,
                    "status" to "sent"
                )
                if (replyingTo != null) {
                    message["replyToMessageId"] = replyingTo.id
                    message["replyToMessageContent"] = replyingTo.content
                }

                chatRef.child(messageId).setValue(message).await()

                postSend(messageId)

                val chatId = getChatId()
                val type = if (this@BaseChatViewModel is GroupChatViewModel) "group_message" else "chat_message"
                sendNotifications(tempMessage, textToSend, type, chatId)

                val mentions = extractMentions(textToSend)
                for (mention in mentions) {
                    val uid = getUidByUsername(mention.substring(1))
                    if (uid != null && uid != currentUserId) {
                        val mentionNotificationId = UUID.randomUUID().toString()
                        val mentionMessage = "$mention, вас упомянули в сообщении"

                        FirebaseDatabase.getInstance()
                            .getReference("users/$uid/notifications/$mentionNotificationId")
                            .setValue(
                                mapOf(
                                    "type" to "mention",
                                    "from_uid" to currentUserId,
                                    "message_id" to messageId,
                                    "chat_id" to chatId,
                                    "timestamp" to timestamp,
                                    "message" to mentionMessage
                                )
                            ).await()

                        viewModelScope.launch {
                            try {
                                val api = ApiService.create()
                                val response = api.sendNotification(
                                    type = "mention",
                                    fromUid = currentUserId,
                                    toUid = uid,
                                    message = mentionMessage,
                                    chatId = chatId,
                                    messageId = messageId
                                )
                                if (!response.isSuccessful) {
                                    Log.e("BaseChatViewModel", "Ошибка пуша упоминания: ${response.errorBody()?.string()}")
                                }
                            } catch (e: Exception) {
                                Log.e("BaseChatViewModel", "Ошибка API пуша упоминания: ${e.message}", e)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Ошибка отправки: ${e.message}", e)
                _state.value = _state.value.copy(
                    isSending = false,
                    error = "Ошибка: ${e.message}"
                )
                clearTyping()
            }
        }
    }

    abstract fun postSend(messageId: String)

    abstract fun sendNotifications(message: Message, text: String, type: String, chatId: String)

    abstract fun getChatId(): String

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
                chatRef.child(messageId).removeValue().await()

                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == messageId }
                )

                removeNotificationsForMessage(messageId)
            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Error deleting: ${e.message}", e)
                _state.value = _state.value.copy(error = "Ошибка удаления: ${e.message}")
            }
        }
    }

    abstract fun removeNotificationsForMessage(messageId: String)

    fun loadMoreMessages() {
        viewModelScope.launch {
            if (lastLoadedTimestamp == null || !_state.value.canLoadMore || _state.value.isLoadingMore) {
                return@launch
            }

            try {
                _state.value = _state.value.copy(isLoadingMore = true)

                val endAtTimestamp = (lastLoadedTimestamp!! - 1L).coerceAtLeast(0L).toDouble()
                val snapshot = chatRef
                    .orderByChild("timestamp")
                    .endAt(endAtTimestamp)
                    .limitToLast(pageSize)
                    .get()
                    .await()

                val newMessages = snapshot.children.mapNotNull { it.toMessage() }

                if (newMessages.isNotEmpty()) {
                    val existingIds = _state.value.messages.map { it.id }.toSet()
                    val uniqueNewMessages = newMessages.filterNot { it.id in existingIds }

                    if (uniqueNewMessages.isNotEmpty()) {
                        lastLoadedTimestamp = uniqueNewMessages.minByOrNull { it.timestamp }?.timestamp

                        _state.value = _state.value.copy(
                            messages = (uniqueNewMessages + _state.value.messages).sortedBy { it.timestamp },
                            canLoadMore = snapshot.childrenCount.toInt() >= pageSize,
                            isLoadingMore = false
                        )
                    } else {
                        _state.value = _state.value.copy(
                            isLoadingMore = false,
                            canLoadMore = false
                        )
                    }
                } else {
                    _state.value = _state.value.copy(
                        canLoadMore = false,
                        isLoadingMore = false
                    )
                }
            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Ошибка подгрузки: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoadingMore = false,
                    error = "Ошибка подгрузки: ${e.message}"
                )
            }
        }
    }

    fun loadInitialMessages() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true, error = null)
                val snapshot = chatRef
                    .orderByChild("timestamp")
                    .limitToLast(pageSize)
                    .get()
                    .await()

                val messages = snapshot.children.mapNotNull { snap ->
                    try {
                        snap.toMessage()
                    } catch (e: Exception) {
                        Log.e("BaseChatViewModel", "Error parsing message ${snap.key}: ${e.message}")
                        null
                    }
                }
                if (messages.isNotEmpty()) {
                    lastLoadedTimestamp = messages.minByOrNull { it.timestamp }?.timestamp
                    currentMaxTimestamp = messages.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                } else {
                    Log.w("BaseChatViewModel", "No messages found in initial load for chat: chats/${getChatId()}/messages")
                }

                _state.value = _state.value.copy(
                    messages = messages.sortedBy { it.timestamp },
                    isLoading = false,
                    canLoadMore = snapshot.childrenCount.toInt() >= pageSize,
                    error = if (messages.isEmpty() && snapshot.childrenCount > 0) "Failed to parse messages" else null
                )

                setupNewMessagesListener()
                markMessagesAsRead()
            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Error loading initial messages: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoading = false,
                    error = "Failed to load messages: ${e.message}"
                )
            }
        }
    }

    protected fun setupNewMessagesListener() {
        newMessagesListener?.let { chatRef.removeEventListener(it) }

        newMessagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.toMessage() ?: return
                val exists = _state.value.messages.any { it.id == message.id }
                if (!exists) {
                    _state.value = _state.value.copy(
                        messages = (_state.value.messages + message).sortedBy { it.timestamp }
                    )
                    currentMaxTimestamp = maxOf(currentMaxTimestamp, message.timestamp)
                    Log.d("BaseChatViewModel", "New message added: ${message.id}, timestamp: ${message.timestamp}")
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.toMessage() ?: return
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map { if (it.id == message.id) message else it }
                )
                Log.d("BaseChatViewModel", "Message updated: ${message.id}")
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val removedId = snapshot.key
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == removedId }
                )
                Log.d("BaseChatViewModel", "Message removed: $removedId")
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("BaseChatViewModel", "New messages listener cancelled: ${error.message}")
                _state.value = _state.value.copy(error = "Listener error: ${error.message}")
            }
        }

        chatRef.addChildEventListener(newMessagesListener!!)
        Log.d("BaseChatViewModel", "New messages listener attached to chats/${getChatId()}/messages")
    }

    private fun DataSnapshot.toMessage(): Message? {
        val data = value as? Map<String, Any> ?: run {
            Log.e("BaseChatViewModel", "Invalid message data format for key: ${key}")
            return null
        }
        try {
            val id = key ?: ""
            val senderId = data["senderId"] as? String ?: ""
            val content = data["content"] as? String ?: ""
            val timestamp = when (val ts = data["timestamp"]) {
                is Long -> ts
                is Double -> ts.toLong()
                else -> 0L
            }
            val status = data["status"] as? String ?: "sent"
            val replyToMessageId = data["replyToMessageId"] as? String
            val replyToMessageContent = data["replyToMessageContent"] as? String

            if (id.isEmpty() || senderId.isEmpty() || content.isEmpty()) {
                Log.e("BaseChatViewModel", "Missing required fields for message $id")
                return null
            }
            return Message(
                id = id,
                senderId = senderId,
                content = content,
                timestamp = timestamp,
                status = status,
                replyToMessageId = replyToMessageId,
                replyToMessageContent = replyToMessageContent
            )
        } catch (e: Exception) {
            Log.e("BaseChatViewModel", "Error parsing message ${key}: ${e.message}")
            return null
        }
    }

    fun setInChat(chatId: String?) {
        FirebaseDatabase.getInstance()
            .getReference("users/$currentUserId/in_chat_with")
            .setValue(chatId)
    }

    protected fun updateMessageStatus(messageId: String, newStatus: String, persistToDb: Boolean = true) {
        // Обновляем локально
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { msg ->
                if (msg.id == messageId) msg.copy(status = newStatus) else msg
            }
        )

        // И в БД (если нужно)
        if (persistToDb) {
            try {
                chatRef.child(messageId).child("status").setValue(newStatus)
            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Failed to persist status $newStatus for $messageId: ${e.message}")
            }
        }
    }

    fun markMessagesAsRead() {
        viewModelScope.launch {
            try {
                val unreadMessages = _state.value.messages.filter {
                    it.senderId != currentUserId && it.status != "read"
                }

                for (msg in unreadMessages) {
                    // Обновляем статус
                    updateMessageStatus(msg.id, "read", persistToDb = true)

                    // Удаляем уведомления в БД
                    try {
                        val snapshot = FirebaseDatabase.getInstance()
                            .getReference("users/$currentUserId/notifications")
                            .orderByChild("message_id")
                            .equalTo(msg.id)
                            .get()
                            .await()

                        for (child in snapshot.children) {
                            child.ref.removeValue().await()
                        }
                    } catch (e: Exception) {
                        Log.e("BaseChatViewModel", "Ошибка при удалении уведомлений: ${e.message}", e)
                    }
                }

                Log.d("BaseChatViewModel", "Прочитано сообщений: ${unreadMessages.size}")
            } catch (e: Exception) {
                Log.e("BaseChatViewModel", "Ошибка при прочтении сообщений: ${e.message}", e)
            }
        }
    }

    protected fun getLocalMessage(messageId: String): Message? {
        return _state.value.messages.firstOrNull { it.id == messageId }
    }
}
