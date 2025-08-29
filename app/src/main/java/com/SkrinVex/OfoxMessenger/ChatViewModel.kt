package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val canLoadMore: Boolean = true
)

class ChatViewModel(
    private val currentUserId: String,
    private val friendUid: String
) : ViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    private var lastLoadedTimestamp: Long? = null  // Изменено с lastLoadedMessageKey на timestamp самого старого
    private val pageSize = 20
    private val chatRef = FirebaseDatabase.getInstance().getReference("chats/${getChatId()}/messages")

    private var newMessagesListener: ChildEventListener? = null  // Для удаления при необходимости
    private var currentMaxTimestamp: Long = 0L  // Для listener на новые сообщения

    init {
        loadInitialMessages()
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
            val textToSend = _state.value.messageText.trim()
            if (textToSend.isBlank()) return@launch
            _state.value = _state.value.copy(isSending = true)

            try {
                val messageId = UUID.randomUUID().toString()
                val chatId = getChatId()
                val timestamp = System.currentTimeMillis()

                // Оптимистично добавляем локально
                val tempMessage = Message(
                    id = messageId,
                    senderId = currentUserId,
                    content = textToSend,
                    timestamp = timestamp,
                    status = "sent"
                )
                _state.value = _state.value.copy(
                    messages = (_state.value.messages + tempMessage).sortedBy { it.timestamp },
                    messageText = "", // очищаем поле только для UI
                    isSending = false,
                    error = null
                )

                // Сохраняем в БД
                val message = mapOf(
                    "id" to messageId,
                    "senderId" to currentUserId,
                    "content" to textToSend,
                    "timestamp" to timestamp,
                    "status" to "sent"
                )

                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId")
                    .setValue(message)
                    .await()

                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId/status")
                    .setValue("delivered")
                    .await()

                // --- Пуш в фоне ---
                viewModelScope.launch {
                    try {
                        val api = ApiService.create()
                        val response = api.sendNotification(
                            type = "chat_message",
                            fromUid = currentUserId,
                            toUid = friendUid,
                            message = textToSend,   // <--- ФИКС, а не _state.value.messageText
                            chatId = chatId,
                            messageId = messageId
                        )
                        if (!response.isSuccessful) {
                            Log.e("ChatViewModel", "Ошибка пуша: ${response.errorBody()?.string()}")
                        }
                    } catch (e: Exception) {
                        Log.e("ChatViewModel", "Ошибка вызова API пуша: ${e.message}", e)
                    }
                }

                // --- Уведомления об упоминаниях ---
                val mentions = extractMentions(textToSend)
                for (mention in mentions) {
                    val uid = getUidByUsername(mention)
                    if (uid != null && uid != currentUserId && uid != friendUid) {
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

                        // пуш упоминания тоже в фоне
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
                                    Log.e("ChatViewModel", "Ошибка пуша упоминания: ${response.errorBody()?.string()}")
                                }
                            } catch (e: Exception) {
                                Log.e("ChatViewModel", "Ошибка API пуша упоминания: ${e.message}", e)
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка отправки: ${e.message}", e)
                _state.value = _state.value.copy(
                    isSending = false,
                    error = "Ошибка: ${e.message}"
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
                FirebaseDatabase.getInstance()
                    .getReference("chats/$chatId/messages/$messageId")
                    .removeValue()
                    .await()

                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == messageId }
                )

                val notificationsSnapshot = FirebaseDatabase.getInstance()
                    .getReference("users/$friendUid/notifications")
                    .get()
                    .await()
                notificationsSnapshot.children.forEach { snapshot ->
                    val notificationData = snapshot.value as? Map<String, Any>
                    if (notificationData?.get("type") == "chat_message" && notificationData["message_id"] == messageId) {
                        snapshot.ref.removeValue().await()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error deleting: ${e.message}", e)
                _state.value = _state.value.copy(error = "Ошибка удаления: ${e.message}")
            }
        }
    }

    fun removeNotificationForMessage(messageId: String) {
        viewModelScope.launch {
            try {
                val notificationsSnapshot = FirebaseDatabase.getInstance()
                    .getReference("users/$currentUserId/notifications")
                    .get()
                    .await()
                notificationsSnapshot.children.forEach { snapshot ->
                    val notificationData = snapshot.value as? Map<String, Any>
                    if (notificationData?.get("type") == "chat_message" && notificationData["message_id"] == messageId) {
                        snapshot.ref.removeValue().await()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Error removing notification: ${e.message}", e)
                _state.value = _state.value.copy(error = "Ошибка удаления уведомления: ${e.message}")
            }
        }
    }

    // --- Загрузка сообщений с пагинацией ---

    fun loadMoreMessages() {
        viewModelScope.launch {
            if (lastLoadedTimestamp == null || !_state.value.canLoadMore || _state.value.isLoadingMore) {
                return@launch
            }

            try {
                // Устанавливаем индикатор загрузки
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
                    // Фильтруем дубликаты
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
                Log.e("ChatViewModel", "Ошибка подгрузки: ${e.message}", e)
                _state.value = _state.value.copy(
                    isLoadingMore = false,
                    error = "Ошибка подгрузки: ${e.message}"
                )
            }
        }
    }

    private fun loadInitialMessages() {
        viewModelScope.launch {
            try {
                _state.value = _state.value.copy(isLoading = true)
                val snapshot = chatRef
                    .orderByChild("timestamp")
                    .limitToLast(pageSize)
                    .get()
                    .await()

                val messages = snapshot.children.mapNotNull { it.toMessage() }
                if (messages.isNotEmpty()) {
                    lastLoadedTimestamp = messages.minByOrNull { it.timestamp }?.timestamp
                    currentMaxTimestamp = messages.maxByOrNull { it.timestamp }?.timestamp ?: 0L
                }

                _state.value = _state.value.copy(
                    messages = messages.sortedBy { it.timestamp },
                    isLoading = false,
                    canLoadMore = snapshot.childrenCount.toInt() >= pageSize
                )

                // После initial load запускаем listener на новые сообщения
                setupNewMessagesListener()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка загрузки: ${e.message}", e)
                _state.value = _state.value.copy(isLoading = false, error = "Ошибка загрузки: ${e.message}")
            }
        }
    }

    private fun setupNewMessagesListener() {
        // Удаляем старый listener, если был
        newMessagesListener?.let { chatRef.removeEventListener(it) }

        newMessagesListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.toMessage() ?: return
                if (message.timestamp > currentMaxTimestamp) {
                    val exists = _state.value.messages.any { it.id == message.id }
                    if (!exists) {
                        _state.value = _state.value.copy(
                            messages = (_state.value.messages + message).sortedBy { it.timestamp }
                        )
                        currentMaxTimestamp = maxOf(currentMaxTimestamp, message.timestamp)
                    }
                }
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                val message = snapshot.toMessage() ?: return
                _state.value = _state.value.copy(
                    messages = _state.value.messages.map { if (it.id == message.id) message else it }
                )
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                val removedId = snapshot.key
                _state.value = _state.value.copy(
                    messages = _state.value.messages.filterNot { it.id == removedId }
                )
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatViewModel", "Listener cancelled: ${error.message}")
            }
        }

        val startAtTimestamp = (currentMaxTimestamp + 1L).toDouble()
        chatRef
            .orderByChild("timestamp")
            .startAt(startAtTimestamp)
            .addChildEventListener(newMessagesListener!!)
    }

    private fun DataSnapshot.toMessage(): Message? {
        val data = value as? Map<String, Any> ?: return null
        return Message(
            id = key ?: "",  // Упрощено, так как key == messageId
            senderId = data["senderId"] as? String ?: "",
            content = data["content"] as? String ?: "",
            timestamp = (data["timestamp"] as? Long) ?: 0L,
            status = data["status"] as? String ?: "sent"
        )
    }

    override fun onCleared() {
        super.onCleared()
        newMessagesListener?.let { chatRef.removeEventListener(it) }
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