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
    val status: String,
    val replyToMessageId: String? = null,
    val replyToMessageContent: String? = null
)

data class ChatState(
    val messages: List<Message> = emptyList(),
    val messageText: String = "",
    val isSending: Boolean = false,
    val isLoading: Boolean = true,
    val isLoadingMore: Boolean = false,
    val error: String? = null,
    val canLoadMore: Boolean = true,
    val replyingTo: Message? = null
)

data class UserStatus(
    val isOnline: Boolean = false,
    val lastActive: Long? = null,
    val isTyping: Boolean = false,
    val inChatWith: String? = null
)

class ChatViewModel(
    currentUserId: String,
    private val friendUid: String
) : BaseChatViewModel(currentUserId) {

    override val chatRef: DatabaseReference = if (currentUserId.isEmpty() || friendUid.isEmpty()) {
        Log.e("ChatViewModel", "Невозможно создать chatRef: currentUserId=$currentUserId, friendUid=$friendUid")
        throw IllegalStateException("Невалидные UID: currentUserId или friendUid пусты")
    } else {
        val chatId = getChatId()
        Log.d("ChatViewModel", "Создан chatRef для chats/$chatId/messages")
        FirebaseDatabase.getInstance().getReference("chats/$chatId/messages")
    }

    private val _friendStatus = MutableStateFlow(UserStatus())
    val friendStatus: StateFlow<UserStatus> = _friendStatus.asStateFlow()

    private var friendStatusListener: ValueEventListener? = null

    init {
        startFriendStatusListener()
        loadInitialMessages() // Переносим вызов сюда после создания chatRef
    }

    fun startFriendStatusListener() {
        val ref = FirebaseDatabase.getInstance().getReference("users/$friendUid")
        friendStatusListener = ref.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val isOnline = snapshot.child("online").getValue(Boolean::class.java) ?: false
                val lastActivity = snapshot.child("lastActivity").getValue(Long::class.java)
                val isTyping = snapshot.child("typing").getValue(Boolean::class.java) ?: false
                val inChatWith = snapshot.child("in_chat_with").getValue(String::class.java)

                _friendStatus.value = UserStatus(
                    isOnline = isOnline,
                    lastActive = lastActivity,
                    isTyping = isTyping,
                    inChatWith = inChatWith
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ChatViewModel", "Слушатель статуса друга отменен: ${error.message}")
            }
        })
    }

    override fun onCleared() {
        super.onCleared()
        friendStatusListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$friendUid")
                .removeEventListener(it)
        }
    }

    override fun updateTyping(isTyping: Boolean) {
        FirebaseDatabase.getInstance()
            .getReference("users/$currentUserId/typing")
            .setValue(isTyping)
            .addOnFailureListener { exception ->
                Log.e("ChatViewModel", "Ошибка обновления статуса набора: ${exception.message}")
            }
    }

    override fun clearTyping() {
        updateTyping(false)
    }

    override fun getChatId(): String {
        return if (currentUserId < friendUid) {
            "${currentUserId}_$friendUid"
        } else {
            "${friendUid}_$currentUserId"
        }
    }

    override fun postSend(messageId: String) {
        viewModelScope.launch {
            try {
                chatRef.child("$messageId/status").setValue("delivered").await()
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка установки статуса доставки: ${e.message}")
            }
        }
    }

    override fun sendNotifications(message: Message, text: String, type: String, chatId: String) {
        viewModelScope.launch {
            try {
                val friendSnapshot = FirebaseDatabase.getInstance()
                    .getReference("users/$friendUid/in_chat_with")
                    .get()
                    .await()

                val isFriendInChatWithMe = friendSnapshot.getValue(String::class.java) == currentUserId

                if (!isFriendInChatWithMe) {
                    val api = ApiService.create()
                    val response = api.sendNotification(
                        type = type,
                        fromUid = currentUserId,
                        toUid = friendUid,
                        message = text,
                        chatId = chatId,
                        messageId = message.id
                    )
                    if (!response.isSuccessful) {
                        Log.e("ChatViewModel", "Ошибка отправки уведомления: ${response.errorBody()?.string()}")
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка вызова API уведомлений: ${e.message}", e)
            }
        }
    }

    override fun removeNotificationsForMessage(messageId: String) {
        viewModelScope.launch {
            try {
                val notificationsRef = FirebaseDatabase.getInstance()
                    .getReference("users/$friendUid/notifications")

                val query = notificationsRef.orderByChild("message_id").equalTo(messageId)

                val snapshot = query.get().await()

                for (child in snapshot.children) {
                    val notificationType = child.child("type").getValue(String::class.java)
                    if (notificationType == "chat_message") {
                        child.ref.removeValue().await()
                    }
                }
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Ошибка удаления уведомления: ${e.message}", e)
            }
        }
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
