package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.network.Friend
import com.SkrinVex.OfoxMessenger.network.FriendsResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class FriendsViewState(
    val isLoading: Boolean = false,
    val friendsData: FriendsResponse? = null,
    val error: String? = null
)

class FriendsViewModel(private val uid: String) : ViewModel() {
    private val _state = MutableStateFlow(FriendsViewState())
    val state: StateFlow<FriendsViewState> = _state.asStateFlow()
    private var friendsListener: ValueEventListener? = null
    private val apiService: ApiService = ApiService.create()

    init {
        setupFriendsListener()
    }

    fun loadFriends(searchQuery: String? = null) {
        viewModelScope.launch {
            _state.value = FriendsViewState(isLoading = true)

            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                _state.value = FriendsViewState(error = "Пользователь не авторизован")
                return@launch
            }

            try {
                val db = FirebaseDatabase.getInstance()
                val indicator = object : GenericTypeIndicator<Map<String, Any>>() {}

                // Загружаем друзей
                val friendSnapshot = db.getReference("users/$uid/friends").get().await()
                val friendsList = mutableListOf<Friend>()
                if (friendSnapshot.exists()) {
                    for (fs in friendSnapshot.children) {
                        val fdata = fs.getValue(indicator)
                        if (fdata?.get("status") == "friends") {
                            userProfileFetch(fs.key)?.let { friend ->
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

                // Загружаем всех пользователей
                val usersSnap = db.getReference("users").get().await()
                val otherUsers = usersSnap.children.mapNotNull { userSnap ->
                    val uid2 = userSnap.key ?: return@mapNotNull null
                    if (uid2 == uid) return@mapNotNull null
                    if (friendsList.any { it.id == uid2 }) return@mapNotNull null

                    val m = userSnap.getValue(indicator) ?: return@mapNotNull null
                    val u = Friend(
                        id = uid2,
                        username = m["username"] as? String ?: "",
                        nickname = m["nickname"] as? String,
                        status = m["status"] as? String,
                        profile_photo = m["profile_photo"] as? String
                    )
                    if (searchQuery.isNullOrBlank() ||
                        u.nickname?.contains(searchQuery, true) == true ||
                        u.username.contains(searchQuery, true)
                    ) u else null
                }

                _state.value = FriendsViewState(
                    friendsData = FriendsResponse(
                        success = true,
                        friends = friendsList,
                        friends_count = friendsList.size,
                        other_users = otherUsers,
                        other_users_count = otherUsers.size
                    )
                )

            } catch (e: Exception) {
                val msg = when {
                    e.message?.contains("Permission denied") == true ->
                        "Нет доступа к данным — проверьте права или авторизацию"
                    e.message?.contains("Network") == true ->
                        "Проблемы сети. Попробуйте позже"
                    else ->
                        "Ошибка: ${e.message}"
                }
                Log.e("FriendsVM", msg, e)
                _state.value = FriendsViewState(error = msg)
            }
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
            profile_photo = m["profile_photo"] as? String
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
                loadFriends()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("FriendsViewModel", "Friends listener error: ${error.message}")
                _state.value = FriendsViewState(error = "Ошибка слушателя: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("users/$uid/friends")
            .addValueEventListener(friendsListener!!)
    }

    override fun onCleared() {
        friendsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/friends").removeEventListener(it)
        }
        super.onCleared()
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