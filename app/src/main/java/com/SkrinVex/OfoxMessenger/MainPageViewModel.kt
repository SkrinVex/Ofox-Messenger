package com.SkrinVex.OfoxMessenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.network.MainPageResponse
import com.SkrinVex.OfoxMessenger.network.NewsItem
import com.SkrinVex.OfoxMessenger.network.NotificationItem
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

data class MainPageState(
    val isLoading: Boolean = true,
    val mainPageData: MainPageResponse? = null,
    val error: String? = null
)

class MainPageViewModel(private val uid: String) : ViewModel() {
    private val _state = MutableStateFlow(MainPageState())
    val state: StateFlow<MainPageState> = _state.asStateFlow()
    private val apiService = ApiService.create()
    private var notificationsListener: ValueEventListener? = null
    private var newsListener: ValueEventListener? = null

    init {
        setupRealtimeListeners()
        loadMainPageData()
    }

    fun loadMainPageData() {
        viewModelScope.launch {
            _state.value = MainPageState(isLoading = true)
            try {
                // Загружаем профиль
                val profileSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid")
                        .get()
                        .await()
                }
                val profileData = profileSnapshot.value as? Map<String, Any>
                val profile = if (profileData != null) {
                    ProfileCheckResponse(
                        success = true,
                        username = profileData["username"] as? String,
                        nickname = profileData["nickname"] as? String,
                        email = profileData["email"] as? String ?: FirebaseAuth.getInstance().currentUser?.email,
                        birthday = profileData["birthday"] as? String,
                        status = profileData["status"] as? String,
                        bio = profileData["bio"] as? String,
                        profile_photo = profileData["profile_photo"] as? String,
                        background_photo = profileData["background_photo"] as? String,
                        profile_completion = (profileData["profile_completion"] as? Long)?.toInt()
                            ?: calculateProfileCompletion(profileData)
                    )
                } else {
                    ProfileCheckResponse(
                        success = true,
                        email = FirebaseAuth.getInstance().currentUser?.email,
                        profile_completion = 0
                    )
                }

                // Загружаем новости
                val newsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("news")
                        .get()
                        .await()
                }
                val newsList = mutableListOf<NewsItem>()
                newsSnapshot.children.forEach { child ->
                    val map = child.value as? Map<String, Any>
                    if (map != null) {
                        val newsItem = NewsItem(
                            title = map["title"] as? String ?: "",
                            content = map["content"] as? String ?: "",
                            date = map["date"] as? String ?: "",
                            image_url = map["image_url"] as? String
                        )
                        newsList.add(newsItem)
                    }
                }

                // Загружаем уведомления
                val notificationsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid/notifications")
                        .get()
                        .await()
                }
                val notificationsList = mutableListOf<NotificationItem>()
                notificationsSnapshot.children.forEach { child ->
                    val map = child.value as? Map<String, Any>
                    if (map != null) {
                        val notification = NotificationItem(
                            id = child.key,
                            title = when (map["type"] as? String) {
                                "friend_request" -> "Новая заявка в друзья"
                                "friend_accepted" -> "Заявка в друзья принята"
                                "friend_declined" -> "Заявка в друзья отклонена"
                                "friend_removed" -> "Удален из друзей"
                                else -> map["message"] as? String ?: "Уведомление"
                            },
                            created_at = (map["timestamp"] as? Long)?.let { formatTimestamp(it) } ?: "",
                            content = map["message"] as? String ?: "",
                            from_uid = map["from_uid"] as? String
                        )
                        notificationsList.add(notification)
                    }
                }

                _state.value = MainPageState(
                    isLoading = false,
                    mainPageData = MainPageResponse(
                        success = true,
                        profile = profile,
                        news_feed = newsList,
                        notifications = notificationsList
                    )
                )

            } catch (e: Exception) {
                _state.value = MainPageState(
                    isLoading = false,
                    mainPageData = MainPageResponse(
                        success = true,
                        profile = ProfileCheckResponse(
                            success = true,
                            email = FirebaseAuth.getInstance().currentUser?.email,
                            profile_completion = 0
                        ),
                        news_feed = emptyList(),
                        notifications = emptyList()
                    ),
                    error = "Ошибка сети: ${e.message}"
                )
            }
        }
    }

    fun clearAllNotifications(onComplete: (success: Boolean, message: String) -> Unit) {
        viewModelScope.launch {
            try {
                FirebaseDatabase.getInstance()
                    .getReference("users/$uid/notifications")
                    .removeValue()
                    .await()
                onComplete(true, "Все уведомления удалены")
            } catch (e: Exception) {
                onComplete(false, "Ошибка при удалении уведомлений: ${e.message}")
            }
        }
    }

    private fun setupRealtimeListeners() {
        // Слушатель для уведомлений
        notificationsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/notifications").removeEventListener(it)
        }
        notificationsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadMainPageData()
            }

            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(error = "Ошибка слушателя уведомлений: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("users/$uid/notifications")
            .addValueEventListener(notificationsListener!!)

        // Слушатель для новостей
        newsListener?.let {
            FirebaseDatabase.getInstance().getReference("news").removeEventListener(it)
        }
        newsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadMainPageData()
            }

            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(error = "Ошибка слушателя новостей: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("news")
            .addValueEventListener(newsListener!!)
    }

    fun updateMainPageData(updatedProfile: ProfileCheckResponse) {
        val currentData = _state.value.mainPageData
        if (currentData != null) {
            _state.value = _state.value.copy(
                mainPageData = currentData.copy(profile = updatedProfile)
            )
        } else {
            loadMainPageData()
        }
    }

    private fun calculateProfileCompletion(profileData: Map<String, Any?>?): Int {
        val fields = listOf("username", "nickname", "email", "birthday", "status", "bio", "profile_photo", "background_photo")
        val filledFields = fields.count { field ->
            profileData?.get(field)?.toString()?.isNotBlank() == true
        }
        return (filledFields * 100) / fields.size
    }

    private fun formatTimestamp(timestamp: Long): String {
        val date = Date(timestamp)
        val formatter = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
        return formatter.format(date)
    }

    override fun onCleared() {
        notificationsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/notifications").removeEventListener(it)
        }
        newsListener?.let {
            FirebaseDatabase.getInstance().getReference("news").removeEventListener(it)
        }
        super.onCleared()
    }
}

class MainPageViewModelFactory(private val uid: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(MainPageViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return MainPageViewModel(uid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}