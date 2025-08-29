package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.network.Friend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class FriendsViewState(
    val isLoading: Boolean = false,
    val friends: List<Friend> = emptyList(),
    val users: List<Friend> = emptyList(),
    val error: String? = null
)

class FriendsViewModel(private val uid: String) : ViewModel() {
    private val _state = MutableStateFlow(FriendsViewState())
    val state: StateFlow<FriendsViewState> = _state.asStateFlow()

    private var friendsListener: ValueEventListener? = null
    private val apiService: ApiService = ApiService.create()

    // для постраничной загрузки
    private var lastKey: String? = null
    private var searchMode = false

    init {
        setupFriendsListener()
    }

    fun loadFriends(searchQuery: String? = null) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)

            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                _state.value = FriendsViewState(error = "Пользователь не авторизован")
                return@launch
            }

            try {
                val db = FirebaseDatabase.getInstance()
                val indicator = object : GenericTypeIndicator<Map<String, Any>>() {}

                val friendSnapshot = db.getReference("users/$uid/friends").get().await()
                val friendsList = mutableListOf<Friend>()

                if (friendSnapshot.exists()) {
                    for (fs in friendSnapshot.children) {
                        val fdata = fs.getValue(indicator)
                        if (fdata?.get("status") == "friends") {
                            val friendUid = fs.key
                            val friend = userProfileFetch(friendUid)
                            if (friend != null && !shouldHideUser(friendUid)) {
                                if (searchQuery.isNullOrBlank() ||
                                    friend.nickname?.contains(searchQuery, true) == true ||
                                    friend.username.contains(searchQuery, true)
                                ) {
                                    friendsList.add(friend)
                                }
                            }
                        }
                    }
                }

                // --- Если это поиск и друзей не нашли, включаем поиск по пользователям ---
                if (!searchQuery.isNullOrBlank()) {
                    if (friendsList.isNotEmpty()) {
                        // нашли среди друзей — показываем их
                        searchMode = false
                        _state.value = FriendsViewState(
                            friends = friendsList,
                            users = emptyList(),
                            isLoading = false
                        )
                        // Запускаем слушатели онлайн статуса для найденных друзей
                        setupOnlineStatusListeners(friendsList.map { it.id })
                    } else {
                        // среди друзей нет — идем в поиск по пользователям
                        searchMode = true
                        lastKey = null
                        _state.value = FriendsViewState(
                            friends = emptyList(),
                            users = emptyList(),
                            isLoading = true
                        )
                        loadMoreUsers(searchQuery)
                    }
                } else {
                    // обычный режим без поиска
                    searchMode = false
                    _state.value = FriendsViewState(
                        friends = friendsList,
                        users = emptyList(),
                        isLoading = false
                    )
                    // Запускаем слушатели онлайн статуса для всех друзей
                    setupOnlineStatusListeners(friendsList.map { it.id })
                }

            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("Permission denied") == true ->
                        "Нет доступа к данным"
                    e.message?.contains("Network") == true ->
                        "Проблемы сети"
                    else -> "Ошибка: ${e.message}"
                }
                Log.e("FriendsVM", msg, e)
                _state.value = FriendsViewState(error = msg)
            }
        }
    }

    fun loadMoreUsers(searchQuery: String? = null) {
        viewModelScope.launch {
            try {
                val db = FirebaseDatabase.getInstance()
                val usersRef = db.getReference("users")
                val indicator = object : GenericTypeIndicator<Map<String, Any>>() {}
                val chunkSize = 30

                val query = if (lastKey == null) {
                    usersRef.orderByKey().limitToFirst(chunkSize)
                } else {
                    usersRef.orderByKey().startAfter(lastKey!!).limitToFirst(chunkSize)
                }

                val snap = query.get().await()
                if (!snap.exists()) return@launch

                val newUsers = mutableListOf<Friend>()
                for (userSnap in snap.children) {
                    lastKey = userSnap.key
                    val uid2 = userSnap.key ?: continue
                    if (uid2 == uid) continue
                    if (_state.value.friends.any { it.id == uid2 }) continue

                    val m = userSnap.getValue(indicator) ?: continue
                    val email = m["email"] as? String?
                    val isDisabled = m["is_disabled"] as? Boolean ?: false
                    if (email.isNullOrBlank() || isDisabled) continue

                    val u = Friend(
                        id = uid2,
                        username = m["username"] as? String ?: "",
                        nickname = m["nickname"] as? String,
                        status = m["status"] as? String,
                        profile_photo = m["profile_photo"] as? String,
                        isOnline = m["online"] as? Boolean ?: false
                    )

                    if (searchQuery.isNullOrBlank() ||
                        u.nickname?.contains(searchQuery, true) == true ||
                        u.username.contains(searchQuery, true)
                    ) newUsers.add(u)
                }

                val allUsers = _state.value.users + newUsers
                _state.value = _state.value.copy(
                    users = allUsers,
                    isLoading = false
                )

                // Добавляем слушатели онлайн статуса для новых пользователей
                val newUserIds = newUsers.map { it.id }
                if (newUserIds.isNotEmpty()) {
                    setupOnlineStatusListenersForNewUsers(newUserIds)
                }

            } catch (e: Exception) {
                Log.e("FriendsVM", "loadMoreUsers error: ${e.message}")
            }
        }
    }

    private fun setupOnlineStatusListenersForNewUsers(userIds: List<String>) {
        userIds.forEach { userId ->
            // Не добавляем если уже есть слушатель для этого пользователя
            if (!onlineStatusListeners.containsKey(userId)) {
                val listener = object : ValueEventListener {
                    override fun onDataChange(snapshot: DataSnapshot) {
                        val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                        updateFriendOnlineStatus(userId, isOnline)
                    }

                    override fun onCancelled(error: DatabaseError) {
                        Log.e("FriendsVM", "Online status listener cancelled: ${error.message}")
                    }
                }

                FirebaseDatabase.getInstance()
                    .getReference("users/$userId/online")
                    .addValueEventListener(listener)

                onlineStatusListeners[userId] = listener
            }
        }
    }

    private val onlineStatusListeners = mutableMapOf<String, ValueEventListener>()

    private fun setupOnlineStatusListeners(friendIds: List<String>) {
        // Очищаем старые слушатели
        clearOnlineStatusListeners()

        friendIds.forEach { friendId ->
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    val isOnline = snapshot.getValue(Boolean::class.java) ?: false
                    updateFriendOnlineStatus(friendId, isOnline)
                }

                override fun onCancelled(error: DatabaseError) {
                    Log.e("FriendsVM", "Online status listener cancelled: ${error.message}")
                }
            }

            FirebaseDatabase.getInstance()
                .getReference("users/$friendId/online")
                .addValueEventListener(listener)

            onlineStatusListeners[friendId] = listener
        }
    }

    private fun updateFriendOnlineStatus(friendId: String, isOnline: Boolean) {
        val currentState = _state.value
        val updatedFriends = currentState.friends.map { friend ->
            if (friend.id == friendId) friend.copy(isOnline = isOnline) else friend
        }
        val updatedUsers = currentState.users.map { user ->
            if (user.id == friendId) user.copy(isOnline = isOnline) else user
        }

        _state.value = currentState.copy(
            friends = updatedFriends,
            users = updatedUsers
        )
    }

    private fun clearOnlineStatusListeners() {
        onlineStatusListeners.forEach { (friendId, listener) ->
            FirebaseDatabase.getInstance()
                .getReference("users/$friendId/online")
                .removeEventListener(listener)
        }
        onlineStatusListeners.clear()
    }

    override fun onCleared() {
        clearOnlineStatusListeners()
        friendsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/friends").removeEventListener(it)
        }
        super.onCleared()
    }

    private suspend fun shouldHideUser(uid: String?): Boolean {
        if (uid == null) return true
        return try {
            val snap = FirebaseDatabase.getInstance().getReference("users/$uid").get().await()
            if (!snap.exists()) return true

            val data = snap.value as? Map<String, Any?> ?: return true
            val email = data["email"] as? String?
            val isDisabled = data["is_disabled"] as? Boolean ?: false

            email.isNullOrBlank() || isDisabled
        } catch (e: Exception) {
            Log.e("FriendsVM", "Ошибка при проверке is_disabled/email: ${e.message}")
            true
        }
    }

    private suspend fun userProfileFetch(friendUid: String?): Friend? {
        if (friendUid == null) return null
        val snap = FirebaseDatabase.getInstance().getReference("users/$friendUid").get().await()
        val indicator = object : GenericTypeIndicator<Map<String, Any>>() {}
        val m = snap.getValue(indicator) ?: return null

        return Friend(
            id = friendUid,
            username = m["username"] as? String ?: "",
            nickname = m["nickname"] as? String,
            status = m["status"] as? String,
            profile_photo = m["profile_photo"] as? String,
            isOnline = m["online"] as? Boolean ?: false // добавляем получение онлайн статуса
        )
    }

    fun searchFriends(query: String) {
        loadFriends(query)
    }

    private fun setupFriendsListener() {
        friendsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/friends").removeEventListener(it)
        }
        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (!searchMode) loadFriends()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FriendsViewModel", "Friends listener error: ${error.message}")
                _state.value = FriendsViewState(error = "Ошибка слушателя: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("users/$uid/friends")
            .addValueEventListener(friendsListener!!)
    }
}

class FriendsViewModelFactory(private val uid: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FriendsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return FriendsViewModel(uid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}