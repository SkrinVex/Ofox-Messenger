package com.SkrinVex.OfoxMessenger

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

data class ProfileViewState(
    val isLoading: Boolean = true,
    val profileData: ProfileCheckResponse? = null,
    val error: String? = null,
    val isOwnProfile: Boolean = true,
    val isProcessingFriendRequest: Boolean = false
)

class ProfileViewModel(
    private val uid: String,
    private val friendUid: String? = null
) : ViewModel() {
    private val _state = MutableStateFlow(ProfileViewState())
    val state: StateFlow<ProfileViewState> = _state.asStateFlow()
    private var friendListener: ValueEventListener? = null

    init {
        loadProfileData()
        if (friendUid != null) {
            setupFriendshipListener()
        }
    }

    fun loadProfileData() {
        viewModelScope.launch {
            _state.value = ProfileViewState(isLoading = true, isOwnProfile = friendUid == null)
            try {
                val targetUid = friendUid ?: uid
                Log.d("ProfileViewModel", "Loading profile for UID: $targetUid")

                // Проверяем авторизацию
                val currentUser = FirebaseAuth.getInstance().currentUser
                if (currentUser == null) {
                    _state.value = ProfileViewState(
                        isLoading = false,
                        error = "Пользователь не авторизован",
                        isOwnProfile = friendUid == null
                    )
                    return@launch
                }

                // Загружаем профиль
                val profileSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$targetUid")
                        .get()
                        .await()
                }
                val profileData = profileSnapshot.value as? Map<String, Any>
                val profile = if (profileData != null && profileSnapshot.exists()) {
                    ProfileCheckResponse(
                        success = true,
                        user_id = targetUid,
                        username = profileData["username"] as? String,
                        nickname = profileData["nickname"] as? String,
                        email = profileData["email"] as? String ?: currentUser.email,
                        birthday = profileData["birthday"] as? String,
                        status = profileData["status"] as? String,
                        bio = profileData["bio"] as? String,
                        profile_photo = profileData["profile_photo"] as? String,
                        background_photo = profileData["background_photo"] as? String,
                        profile_completion = (profileData["profile_completion"] as? Long)?.toInt() ?: calculateProfileCompletion(profileData),
                        is_friend = false,
                        friendship_status = if (friendUid == null) "own_profile" else "none"
                    )
                } else {
                    ProfileCheckResponse(
                        success = true,
                        user_id = targetUid,
                        email = currentUser.email,
                        profile_completion = 0,
                        friendship_status = if (friendUid == null) "own_profile" else "none"
                    )
                }

                // Загружаем статус дружбы
                val finalProfile = if (friendUid != null) {
                    val friendshipSnapshot = withContext(Dispatchers.IO) {
                        FirebaseDatabase.getInstance()
                            .getReference("users/$uid/friends/$friendUid")
                            .get()
                            .await()
                    }
                    val friendshipData = friendshipSnapshot.value as? Map<String, Any>
                    profile.copy(
                        friendship_status = friendshipData?.get("status") as? String ?: "none",
                        is_friend = friendshipData?.get("is_friend") as? Boolean ?: false
                    )
                } else {
                    profile.copy(friendship_status = "own_profile")
                }

                Log.d("ProfileViewModel", "Profile loaded: user_id=${finalProfile.user_id}, friendship_status=${finalProfile.friendship_status}, is_friend=${finalProfile.is_friend}")

                _state.value = ProfileViewState(
                    isLoading = false,
                    profileData = finalProfile,
                    isOwnProfile = friendUid == null
                )
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error loading profile: ${e.message}", e)
                _state.value = ProfileViewState(
                    isLoading = false,
                    error = "Ошибка загрузки профиля: ${e.message}",
                    isOwnProfile = friendUid == null
                )
            }
        }
    }

    fun updateProfile(updatedProfile: ProfileCheckResponse) {
        _state.value = _state.value.copy(profileData = updatedProfile)
    }

    fun sendFriendRequest(targetUid: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessingFriendRequest = true)
            try {
                val db = FirebaseDatabase.getInstance()

                // Проверяем существование пользователя
                val targetSnapshot = withContext(Dispatchers.IO) {
                    db.getReference("users/$targetUid").get().await()
                }
                if (!targetSnapshot.exists()) {
                    _state.value = _state.value.copy(isProcessingFriendRequest = false)
                    onResult(false, "Пользователь не существует")
                    return@launch
                }

                // Проверяем текущий статус дружбы
                val currentFriendship = withContext(Dispatchers.IO) {
                    db.getReference("users/$uid/friends/$targetUid").get().await()
                }
                val currentStatus = (currentFriendship.value as? Map<String, Any>)?.get("status") as? String
                if (currentStatus in listOf("request_sent", "friends")) {
                    _state.value = _state.value.copy(isProcessingFriendRequest = false)
                    onResult(false, "Заявка уже отправлена или пользователь в друзьях")
                    return@launch
                }

                // Отправляем заявку
                val notificationId = UUID.randomUUID().toString()
                db.getReference("users/$targetUid/friends/$uid")
                    .setValue(mapOf("status" to "request_received", "is_friend" to false))
                    .await()
                db.getReference("users/$uid/friends/$targetUid")
                    .setValue(mapOf("status" to "request_sent", "is_friend" to false))
                    .await()
                db.getReference("users/$targetUid/notifications/$notificationId")
                    .setValue(mapOf(
                        "type" to "friend_request",
                        "from_uid" to uid,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "Новое приглашение в друзья"
                    )).await()

                val currentProfile = _state.value.profileData
                if (currentProfile != null) {
                    _state.value = _state.value.copy(
                        profileData = currentProfile.copy(friendship_status = "request_sent"),
                        isProcessingFriendRequest = false
                    )
                }
                onResult(true, "Заявка отправлена")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error sending friend request: ${e.message}", e)
                _state.value = _state.value.copy(isProcessingFriendRequest = false)
                onResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    fun cancelFriendRequest(targetUid: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessingFriendRequest = true)
            try {
                val db = FirebaseDatabase.getInstance()
                db.getReference("users/$targetUid/friends/$uid").removeValue().await()
                db.getReference("users/$uid/friends/$targetUid").removeValue().await()

                val currentProfile = _state.value.profileData
                if (currentProfile != null) {
                    _state.value = _state.value.copy(
                        profileData = currentProfile.copy(friendship_status = "none"),
                        isProcessingFriendRequest = false
                    )
                }
                onResult(true, "Заявка отменена")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error canceling friend request: ${e.message}", e)
                _state.value = _state.value.copy(isProcessingFriendRequest = false)
                onResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    fun acceptFriendRequest(targetUid: String, notificationId: String?, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessingFriendRequest = true)
            try {
                val db = FirebaseDatabase.getInstance()
                db.getReference("users/$uid/friends/$targetUid")
                    .setValue(mapOf("status" to "friends", "is_friend" to true))
                    .await()
                db.getReference("users/$targetUid/friends/$uid")
                    .setValue(mapOf("status" to "friends", "is_friend" to true))
                    .await()
                // Уведомление для отправителя
                val newNotificationId = UUID.randomUUID().toString()
                db.getReference("users/$targetUid/notifications/$newNotificationId")
                    .setValue(mapOf(
                        "type" to "friend_accepted",
                        "from_uid" to uid,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "Ваша заявка в друзья принята"
                    )).await()

                // Удаляем уведомление о заявке
                if (notificationId != null) {
                    db.getReference("users/$uid/notifications/$notificationId").removeValue().await()
                }

                val currentProfile = _state.value.profileData
                if (currentProfile != null) {
                    _state.value = _state.value.copy(
                        profileData = currentProfile.copy(
                            friendship_status = "friends",
                            is_friend = true
                        ),
                        isProcessingFriendRequest = false
                    )
                }
                onResult(true, "Заявка принята")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error accepting friend request: ${e.message}", e)
                _state.value = _state.value.copy(isProcessingFriendRequest = false)
                onResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    fun declineFriendRequest(targetUid: String, notificationId: String?, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessingFriendRequest = true)
            try {
                val db = FirebaseDatabase.getInstance()
                db.getReference("users/$uid/friends/$targetUid").removeValue().await()
                db.getReference("users/$targetUid/friends/$uid").removeValue().await()
                // Уведомление для отправителя
                val newNotificationId = UUID.randomUUID().toString()
                db.getReference("users/$targetUid/notifications/$newNotificationId")
                    .setValue(mapOf(
                        "type" to "friend_declined",
                        "from_uid" to uid,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "Ваша заявка в друзья отклонена"
                    )).await()

                // Удаляем уведомление о заявке
                if (notificationId != null) {
                    db.getReference("users/$uid/notifications/$notificationId").removeValue().await()
                }

                val currentProfile = _state.value.profileData
                if (currentProfile != null) {
                    _state.value = _state.value.copy(
                        profileData = currentProfile.copy(friendship_status = "none"),
                        isProcessingFriendRequest = false
                    )
                }
                onResult(true, "Заявка отклонена")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error declining friend request: ${e.message}", e)
                _state.value = _state.value.copy(isProcessingFriendRequest = false)
                onResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    fun removeFriend(targetUid: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isProcessingFriendRequest = true)
            try {
                val db = FirebaseDatabase.getInstance()
                db.getReference("users/$uid/friends/$targetUid").removeValue().await()
                db.getReference("users/$targetUid/friends/$uid").removeValue().await()
                // Уведомление для друга
                val notificationId = UUID.randomUUID().toString()
                db.getReference("users/$targetUid/notifications/$notificationId")
                    .setValue(mapOf(
                        "type" to "friend_removed",
                        "from_uid" to uid,
                        "timestamp" to System.currentTimeMillis(),
                        "message" to "Вы были удалены из друзей"
                    )).await()

                val currentProfile = _state.value.profileData
                if (currentProfile != null) {
                    _state.value = _state.value.copy(
                        profileData = currentProfile.copy(friendship_status = "none", is_friend = false),
                        isProcessingFriendRequest = false
                    )
                }
                onResult(true, "Друг удалён")
            } catch (e: Exception) {
                Log.e("ProfileViewModel", "Error removing friend: ${e.message}", e)
                _state.value = _state.value.copy(isProcessingFriendRequest = false)
                onResult(false, "Ошибка: ${e.message}")
            }
        }
    }

    private fun setupFriendshipListener() {
        friendListener?.let { FirebaseDatabase.getInstance().getReference("users/$uid/friends/$friendUid").removeEventListener(it) }
        friendListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val friendshipData = snapshot.value as? Map<String, Any>
                val currentProfile = _state.value.profileData ?: return
                val newStatus = friendshipData?.get("status") as? String ?: "none"
                val isFriend = friendshipData?.get("is_friend") as? Boolean ?: false
                Log.d("ProfileViewModel", "Friendship status updated: $newStatus, is_friend=$isFriend")
                _state.value = _state.value.copy(
                    profileData = currentProfile.copy(
                        friendship_status = newStatus,
                        is_friend = isFriend
                    )
                )
            }

            override fun onCancelled(error: DatabaseError) {
                Log.e("ProfileViewModel", "Friendship listener error: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("users/$uid/friends/$friendUid")
            .addValueEventListener(friendListener!!)
    }

    private fun calculateProfileCompletion(profileData: Map<String, Any?>?): Int {
        val fields = listOf("username", "nickname", "email", "birthday", "status", "bio", "profile_photo", "background_photo")
        val filledFields = fields.count { field ->
            profileData?.get(field)?.toString()?.isNotBlank() == true
        }
        return (filledFields * 100) / fields.size
    }

    override fun onCleared() {
        friendListener?.let { FirebaseDatabase.getInstance().getReference("users/$uid/friends/$friendUid").removeEventListener(it) }
        super.onCleared()
    }
}

class ProfileViewModelFactory(
    private val uid: String,
    private val friendUid: String? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ProfileViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ProfileViewModel(uid, friendUid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}