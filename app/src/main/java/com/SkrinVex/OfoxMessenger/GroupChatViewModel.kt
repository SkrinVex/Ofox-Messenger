package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.google.firebase.database.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class GroupStatus(
    val typingUsers: List<String> = emptyList(),
    val onlineCount: Int = 0,
    val onlineNames: List<String> = emptyList(),
    val memberCount: Int = 0 // <-- добавил поле количества участников
)

class GroupChatViewModel(
    currentUserId: String,
    private val groupId: String
) : BaseChatViewModel(currentUserId) {

    override val chatRef: DatabaseReference = FirebaseDatabase.getInstance()
        .getReference("group_chats/$groupId/messages")

    private val readReceiptsRef = FirebaseDatabase.getInstance()
        .getReference("group_chats/$groupId/read_receipts")

    private val _groupStatus = MutableStateFlow(GroupStatus())
    val groupStatus: StateFlow<GroupStatus> = _groupStatus.asStateFlow()

    private var members: List<String> = emptyList()
    private var memberNames: Map<String, String> = emptyMap()
    private var memberPhotos: Map<String, String> = emptyMap()

    private var typingListener: ValueEventListener? = null
    private val onlineListeners = mutableMapOf<String, ValueEventListener>()
    private val onlineMap = mutableMapOf<String, Boolean>()

    // слушатель read receipts
    private var readReceiptsListener: ChildEventListener? = null

    init {
        if (currentUserId.isBlank() || groupId.isBlank()) {
            Log.e("GroupChatViewModel", "Invalid IDs: currentUserId=$currentUserId, groupId=$groupId")
        }
        observeGroupMembers()
    }

    private fun observeGroupMembers() {
        val membersRef = FirebaseDatabase.getInstance()
            .getReference("group_chats/$groupId/meta/members")

        membersRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newMembers = snapshot.children.mapNotNull { it.key }
                Log.d("GroupChatViewModel", "Group members updated: $newMembers")

                members = newMembers
                // обновляем в groupStatus: количество участников
                _groupStatus.value = _groupStatus.value.copy(memberCount = newMembers.size)

                loadMemberDetails(newMembers)
                setupTypingListener()
                setupOnlineListeners()
                setupReadReceiptsListener() // (re)создаём слушатель, т.к. members могли измениться
                loadInitialMessages() // загружаем только после актуализации
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupChatViewModel", "Failed to read group members: ${error.message}")
                loadInitialMessages()
            }
        })
    }

    private fun loadMemberDetails(uids: List<String>) {
        viewModelScope.launch {
            val names = mutableMapOf<String, String>()
            val photos = mutableMapOf<String, String>()
            for (uid in uids) {
                try {
                    val userSnap = FirebaseDatabase.getInstance()
                        .getReference("users/$uid")
                        .get()
                        .await()
                    val nickname = userSnap.child("nickname").getValue(String::class.java)
                    val username = userSnap.child("username").getValue(String::class.java)
                    val displayName = nickname?.takeIf { it.isNotBlank() }
                        ?: username?.takeIf { it.isNotBlank() }
                        ?: "Пользователь"
                    names[uid] = displayName
                    photos[uid] = userSnap.child("profile_photo").getValue(String::class.java) ?: ""
                } catch (e: Exception) {
                    Log.w("GroupChatViewModel", "Failed to load user $uid: ${e.message}")
                    names[uid] = "Пользователь"
                    photos[uid] = ""
                }
            }
            memberNames = names
            memberPhotos = photos
        }
    }

    private fun setupTypingListener() {
        val typingRef = FirebaseDatabase.getInstance()
            .getReference("group_chats/$groupId/typing")

        typingListener?.let { typingRef.removeEventListener(it) }

        typingListener = typingRef.addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val typingUids = snapshot.children
                    .filter { it.getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }
                    .filter { it != currentUserId }

                val typingNames = typingUids.map { memberNames[it] ?: it }
                _groupStatus.value = _groupStatus.value.copy(typingUsers = typingNames)

                Log.d("GroupChatViewModel", "Typing users: $typingNames")
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupChatViewModel", "Typing listener cancelled: ${error.message}")
            }
        })
    }

    private fun setupOnlineListeners() {
        for ((uid, listener) in onlineListeners) {
            FirebaseDatabase.getInstance().getReference("users/$uid/online")
                .removeEventListener(listener)
        }
        onlineListeners.clear()
        onlineMap.clear()

        for (uid in members) {
            if (uid == currentUserId) continue
            val ref = FirebaseDatabase.getInstance().getReference("users/$uid/online")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                    onlineMap[uid] = isOnline
                    val onlineNames = onlineMap.filterValues { it }.keys.map { memberNames[it] ?: it }
                    _groupStatus.value = _groupStatus.value.copy(
                        onlineCount = onlineNames.size,
                        onlineNames = onlineNames
                    )
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("GroupChatViewModel", "Online listener cancelled: ${error.message}")
                }
            }
            ref.addValueEventListener(listener)
            onlineListeners[uid] = listener
        }
    }

    override fun onCleared() {
        super.onCleared()
        typingListener?.let {
            FirebaseDatabase.getInstance()
                .getReference("group_chats/$groupId/typing")
                .removeEventListener(it)
        }
        for ((uid, listener) in onlineListeners) {
            FirebaseDatabase.getInstance().getReference("users/$uid/online")
                .removeEventListener(listener)
        }
        onlineListeners.clear()

        readReceiptsListener?.let {
            readReceiptsRef.removeEventListener(it)
        }
    }

    override fun updateTyping(isTyping: Boolean) {
        if (groupId.isNotBlank() && currentUserId.isNotBlank()) {
            FirebaseDatabase.getInstance()
                .getReference("group_chats/$groupId/typing/$currentUserId")
                .setValue(isTyping)
                .addOnFailureListener { e ->
                    Log.e("GroupChatViewModel", "Failed to update typing: ${e.message}")
                }
        }
    }

    override fun clearTyping() {
        updateTyping(false)
    }

    fun getMemberName(uid: String): String? = memberNames[uid]
    fun getMemberPhoto(uid: String): String? = memberPhotos[uid]

    override fun getChatId(): String = groupId

    override fun postSend(messageId: String) {
        // для групп — помечаем, что отправитель прочитал своё же сообщение
        try {
            readReceiptsRef.child(messageId).child(currentUserId).setValue(true)
        } catch (e: Exception) {
            Log.e("GroupChatViewModel", "Failed to set initial read receipt: ${e.message}")
        }
    }

    /**
     * --- Система read receipts ---
     *
     * Структура в БД:
     * group_chats/$groupId/read_receipts/$messageId/$uid = true
     *
     * Логика:
     * - когда под snapshot для messageId появляется набор uid'ов,
     *   и количество uid'ов (кроме sender) >= (members.size - 1),
     *   тогда ставим status="read" в messages/$messageId
     */
    private fun setupReadReceiptsListener() {
        // удаляем старый, если есть
        readReceiptsListener?.let { readReceiptsRef.removeEventListener(it) }

        readReceiptsListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                handleReceiptsForMessage(snapshot)
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                handleReceiptsForMessage(snapshot)
            }

            override fun onChildRemoved(snapshot: DataSnapshot) {
                // ничего не делаем
            }

            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {
                Log.e("GroupChatViewModel", "ReadReceipts listener cancelled: ${error.message}")
            }
        }

        readReceiptsRef.addChildEventListener(readReceiptsListener!!)
    }

    private fun handleReceiptsForMessage(snapshot: DataSnapshot) {
        val messageId = snapshot.key ?: return
        val uidsWhoRead = snapshot.children.mapNotNull { it.key }.toSet()

        // получим сообщение локально (в base)
        val message = getLocalMessage(messageId)

        // если у нас нет списка участников — нельзя корректно определить "все"
        if (members.isEmpty()) {
            Log.w("GroupChatViewModel", "Members empty, postponing receipts check for $messageId")
            return
        }

        // считаем, сколько участников (кроме отправителя) должны прочитать
        val requiredReaders = members.filter { it != message?.senderId }.toSet()

        // если все обязательные uid в uidsWhoRead -> пометить сообщение как read
        if (uidsWhoRead.containsAll(requiredReaders)) {
            // обновим статус локально и в БД (через BaseChatViewModel helper)
            updateMessageStatus(messageId, "read", persistToDb = true)
            Log.d("GroupChatViewModel", "Message $messageId marked as READ (all members read)")
        } else {
            // можно пометить как delivered, если хотя бы кто-то прочитал (опционально)
            // если хотите — можно добавить логику для "delivered" здесь
            // например: если uidsWhoRead.isNotEmpty() && message?.status != "read" -> set "delivered"
            if (uidsWhoRead.isNotEmpty() && message?.status != "read") {
                updateMessageStatus(messageId, "delivered", persistToDb = true)
                Log.d("GroupChatViewModel", "Message $messageId marked as DELIVERED (partial reads)")
            }
        }
    }

    /**
     * Вызывать из UI (например, в onResume активити/фрагмента чата),
     * чтобы пометить видимые/только что полученные сообщения как прочитанные текущим пользователем.
     * Простая реализация — помечаем все сообщения, которые не прочитаны и не отправлены текущим пользователем.
     */
    fun markAllVisibleMessagesRead() {
        viewModelScope.launch {
            val messages = state.value.messages
            for (msg in messages) {
                // не ставим read для собственных сообщений — отправитель уже добавлен в receipts в postSend
                if (msg.senderId == currentUserId) continue
                try {
                    readReceiptsRef.child(msg.id).child(currentUserId).setValue(true)
                } catch (e: Exception) {
                    Log.e("GroupChatViewModel", "Failed marking message ${msg.id} read: ${e.message}")
                }
            }
        }
    }

    /**
     * Если нужно помечать конкретное сообщение как прочитанное (например при открытии детального view)
     */
    fun markMessageRead(messageId: String) {
        viewModelScope.launch {
            try {
                readReceiptsRef.child(messageId).child(currentUserId).setValue(true)
            } catch (e: Exception) {
                Log.e("GroupChatViewModel", "Failed marking message $messageId read: ${e.message}")
            }
        }
    }

    override fun sendNotifications(message: Message, text: String, type: String, chatId: String) {
        viewModelScope.launch {
            // Получаем данные группы
            val groupData = try {
                FirebaseDatabase.getInstance()
                    .getReference("group_chats/$groupId/meta")
                    .get()
                    .await()
                    .value as? Map<String, Any>
            } catch (e: Exception) {
                null
            }

            val groupName = groupData?.get("name") as? String ?: "Групповой чат"
            val groupPhoto = groupData?.get("photo") as? String

            for (memberUid in members) {
                if (memberUid == currentUserId) continue
                try {
                    val memberSnapshot = FirebaseDatabase.getInstance()
                        .getReference("users/$memberUid/in_chat_with")
                        .get()
                        .await()
                    if (memberSnapshot.getValue(String::class.java) != "group_$groupId") {
                        val api = ApiService.create()
                        val response = api.sendGroupNotification(
                            type = type,
                            fromUid = currentUserId,
                            toUid = memberUid,
                            message = text,
                            chatId = chatId,
                            messageId = message.id,
                            groupName = groupName,
                            groupPhoto = groupPhoto
                        )
                        if (!response.isSuccessful) {
                            Log.e("GroupChatViewModel", "Ошибка отправки уведомления участнику $memberUid: ${response.errorBody()?.string()}")
                        } else {
                            Log.d("GroupChatViewModel", "Уведомление успешно отправлено участнику $memberUid")
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GroupChatViewModel", "Push error to $memberUid: ${e.message}")
                }
            }
        }
    }

    fun russianPlural(count: Int, one: String, few: String, many: String): String {
        val c = count % 100
        return if (c in 11..19) {
            many
        } else {
            when (count % 10) {
                1 -> one
                2, 3, 4 -> few
                else -> many
            }
        }
    }

    override fun removeNotificationsForMessage(messageId: String) {
        viewModelScope.launch {
            for (uid in members) {
                if (uid == currentUserId) continue
                try {
                    val notificationsRef = FirebaseDatabase.getInstance()
                        .getReference("users/$uid/notifications")

                    val query = notificationsRef.orderByChild("message_id").equalTo(messageId)

                    val snapshot = query.get().await()

                    for (child in snapshot.children) {
                        // Дополнительно проверяем тип, чтобы случайно не удалить что-то не то
                        val notificationType = child.child("type").getValue(String::class.java)
                        if (notificationType == "group_message") {
                            child.ref.removeValue().await()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("GroupChatViewModel", "Remove notif error for $uid: ${e.message}")
                }
            }
        }
    }
}

class GroupChatViewModelFactory(
    private val currentUserId: String,
    private val groupId: String
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GroupChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return GroupChatViewModel(currentUserId, groupId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
