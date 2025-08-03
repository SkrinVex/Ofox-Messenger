package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.regex.Pattern

data class PostsState(
    val isLoading: Boolean = true,
    val posts: List<PostItem> = emptyList(),
    val friends: List<String> = emptyList(),
    val error: String? = null
)

data class PostItem(
    val id: String? = null,
    val user_id: String,
    val username: String? = null,
    val nickname: String? = null,
    val profile_photo: String? = null,
    val title: String,
    val content: String,
    val image_urls: List<String>? = null,
    val created_at: String,
    val is_edited: Boolean = false,
    val comments: List<CommentItem> = emptyList(),
    val likes: Map<String, String?> = emptyMap(), // Изменено на Map<String, String?>
    val userReaction: String? = null
)

data class CommentItem(
    val id: String,
    val user_id: String,
    val username: String? = null,
    val nickname: String? = null,
    val profile_photo: String? = null,
    val content: String,
    val created_at: String
)

class PostsViewModel(private val uid: String) : ViewModel() {
    private val _state = MutableStateFlow(PostsState())
    val state: StateFlow<PostsState> = _state.asStateFlow()
    private val apiService = ApiService.create()
    private var postsListener: ValueEventListener? = null
    private var friendsListener: ValueEventListener? = null
    private val TAG = "PostsActivityDebug"

    init {
        setupRealtimeListeners()
        loadPostsData()
    }

    fun loadPostsData() {
        viewModelScope.launch {
            _state.value = PostsState(isLoading = true)
            try {
                Log.d(TAG, "Loading posts data for user: $uid")
                // Загружаем друзей
                val friendsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid/friends")
                        .get()
                        .await()
                }
                val friendsList = friendsSnapshot.children
                    .filter { it.child("is_friend").getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }

                // Загружаем посты
                val postsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("posts")
                        .get()
                        .await()
                }
                val postsList = mutableListOf<PostItem>()
                postsSnapshot.children.forEach { child ->
                    val map = child.value as? Map<String, Any>
                    if (map != null) {
                        val userSnapshot = withContext(Dispatchers.IO) {
                            FirebaseDatabase.getInstance()
                                .getReference("users/${map["user_id"]}")
                                .get()
                                .await()
                        }
                        val userData = userSnapshot.value as? Map<String, Any>
                        val imageUrls = (map["image_urls"] as? List<*>)?.mapNotNull { it as? String }
                        val comments = mutableListOf<CommentItem>()
                        val commentsSnapshot = withContext(Dispatchers.IO) {
                            FirebaseDatabase.getInstance()
                                .getReference("posts/${child.key}/comments")
                                .get()
                                .await()
                        }
                        commentsSnapshot.children.forEach { commentChild ->
                            val commentMap = commentChild.value as? Map<String, Any>
                            if (commentMap != null) {
                                val commentUserSnapshot = withContext(Dispatchers.IO) {
                                    FirebaseDatabase.getInstance()
                                        .getReference("users/${commentMap["user_id"]}")
                                        .get()
                                        .await()
                                }
                                val commentUserData = commentUserSnapshot.value as? Map<String, Any>
                                comments.add(
                                    CommentItem(
                                        id = commentChild.key ?: "",
                                        user_id = commentMap["user_id"] as? String ?: "",
                                        username = commentUserData?.get("username") as? String,
                                        nickname = commentUserData?.get("nickname") as? String,
                                        profile_photo = commentUserData?.get("profile_photo") as? String,
                                        content = commentMap["content"] as? String ?: "",
                                        created_at = commentMap["created_at"] as? String ?: ""
                                    )
                                )
                            }
                        }
                        // Загружаем реакции
                        val reactionsSnapshot = withContext(Dispatchers.IO) {
                            FirebaseDatabase.getInstance()
                                .getReference("posts/${child.key}/reactions")
                                .get()
                                .await()
                        }
                        val reactions = reactionsSnapshot.children.associate { it.key!! to it.getValue(String::class.java) }
                        val userReaction = reactions[uid]
                        val post = PostItem(
                            id = child.key,
                            user_id = map["user_id"] as? String ?: "",
                            username = userData?.get("username") as? String,
                            nickname = userData?.get("nickname") as? String,
                            profile_photo = userData?.get("profile_photo") as? String,
                            title = map["title"] as? String ?: "",
                            content = map["content"] as? String ?: "",
                            image_urls = imageUrls,
                            created_at = map["created_at"] as? String ?: "",
                            is_edited = map["is_edited"] as? Boolean ?: false,
                            comments = comments,
                            likes = reactions,
                            userReaction = userReaction
                        )
                        postsList.add(post)
                    }
                }

                _state.value = PostsState(
                    isLoading = false,
                    posts = postsList.sortedByDescending { it.created_at },
                    friends = friendsList
                )
                Log.d(TAG, "Posts loaded: ${postsList.size}, Friends: ${friendsList.size}")
            } catch (e: Exception) {
                _state.value = PostsState(
                    isLoading = false,
                    posts = emptyList(),
                    friends = emptyList(),
                    error = "Ошибка загрузки: ${e.message}"
                )
                Log.e(TAG, "Error loading posts: ${e.message}", e)
            }
        }
    }

    private fun uriToFile(context: Context, uri: Uri): File? {
        return try {
            Log.d(TAG, "Converting Uri to File: $uri")
            val file = File(context.cacheDir, "temp_image_${System.currentTimeMillis()}.jpg")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(file).use { output ->
                    input.copyTo(output)
                }
            }
            Log.d(TAG, "File created successfully: ${file.absolutePath}")
            file
        } catch (e: Exception) {
            Log.e(TAG, "Error converting Uri to File: ${e.message}", e)
            null
        }
    }

    fun toggleReaction(postId: String, userId: String, reactionType: String) {
        val dbRef = FirebaseDatabase.getInstance().getReference("posts/$postId/reactions/$userId")
        viewModelScope.launch {
            try {
                val snapshot = dbRef.get().await()
                val currentReaction = snapshot.getValue(String::class.java)

                val newReaction = when {
                    currentReaction == reactionType -> null // Удаляем реакцию, если выбрана та же
                    else -> reactionType // Устанавливаем новую реакцию
                }

                // Обновляем реакцию в Firebase
                dbRef.setValue(newReaction).await()
                Log.d("ReactionUpdate", "Reaction updated: $postId, user: $userId, type: $newReaction")

                // Обновляем локальное состояние
                loadPostsData()
            } catch (e: Exception) {
                Log.e("ReactionError", "Error updating reaction: ${e.message}", e)
            }
        }
    }

    fun getReactionCounts(postId: String, onComplete: (Int, Int) -> Unit) {
        val reactionsRef = FirebaseDatabase.getInstance().getReference("posts/$postId/reactions")
        viewModelScope.launch {
            try {
                val snapshot = reactionsRef.get().await()
                val likes = snapshot.children.count { it.getValue(String::class.java) == "like" }
                val dislikes = snapshot.children.count { it.getValue(String::class.java) == "dislike" }
                Log.d("ReactionCount", "Post $postId: Likes=$likes, Dislikes=$dislikes")
                onComplete(likes, dislikes)
            } catch (e: Exception) {
                Log.e("ReactionCountError", "Error fetching reaction counts: ${e.message}", e)
                onComplete(0, 0)
            }
        }
    }

    // Function to detect links in content and find compatible apps
    fun detectLinksAndApps(content: String, context: Context): Pair<List<String>, List<List<Pair<ResolveInfo, String>>>> {
        val urlPattern = Pattern.compile(
            "(https?://[\\w\\-\\.]+(:\\d+)?(/[\\w\\-\\.]*)*(\\?[\\w\\-\\.=&%]*)?(#[\\w\\-]*)?)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = urlPattern.matcher(content)
        val links = mutableListOf<String>()
        val appsPerLink = mutableListOf<List<Pair<ResolveInfo, String>>>()
        val TAG = "PostsActivityDebug"

        while (matcher.find()) {
            val url = matcher.group()
            links.add(url)
            Log.d(TAG, "Detected URL: $url")

            // Попробуем обработать как обычный URL
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
            }

            // Получаем список приложений для стандартного URL
            val resolveInfo = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            val appsWithLabels = resolveInfo.map { resolve ->
                val label = resolve.loadLabel(context.packageManager).toString()
                Pair(resolve, label)
            }

            // Проверяем кастомные схемы (например, tg://, youtube://)
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: ""
            val customApps = mutableListOf<Pair<ResolveInfo, String>>()
            if (scheme != "http" && scheme != "https") {
                val customIntent = Intent(Intent.ACTION_VIEW, uri)
                val customResolveInfo = context.packageManager.queryIntentActivities(customIntent, PackageManager.MATCH_ALL)
                customResolveInfo.forEach { resolve ->
                    val label = resolve.loadLabel(context.packageManager).toString()
                    customApps.add(Pair(resolve, label))
                }
                Log.d(TAG, "Custom scheme ($scheme) apps: ${customApps.map { it.second }}")
            }

            // Объединяем списки, избегая дубликатов
            val combinedApps = (appsWithLabels + customApps).distinctBy { it.first.activityInfo.packageName }
            appsPerLink.add(combinedApps)
            Log.d(TAG, "Apps for URL $url: ${combinedApps.map { it.second }}")
        }

        return Pair(links, appsPerLink)
    }

    fun createPost(title: String, content: String, imageUris: List<Uri>, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Creating post with title: $title, content: $content, imageUris: ${imageUris.size}")
                if (imageUris.size > 5) {
                    return@launch onComplete(false, "Максимум 5 изображений").also {
                        Log.e(TAG, "Too many images selected: ${imageUris.size}")
                    }
                }
                val postId = FirebaseDatabase.getInstance().getReference("posts").push().key
                    ?: return@launch onComplete(false, "Ошибка создания ID поста").also {
                        Log.e(TAG, "Failed to generate post ID")
                    }
                val imageUrls = mutableListOf<String>()

                for (uri in imageUris) {
                    val file = uriToFile(context, uri)
                    if (file == null) {
                        Log.e(TAG, "Failed to convert Uri to File: $uri")
                        return@launch onComplete(false, "Ошибка обработки изображения")
                    }
                    val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                    val imagePart = MultipartBody.Part.createFormData("image", file.name, requestFile)
                    val userId = uid.toRequestBody("text/plain".toMediaTypeOrNull())
                    val type = "post_image".toRequestBody("text/plain".toMediaTypeOrNull())

                    Log.d(TAG, "Uploading image for post: $postId, uri: $uri")
                    val response = apiService.uploadImage(imagePart, userId, type)
                    if (response.isSuccessful && response.body()?.success == true) {
                        response.body()?.image_url?.let { imageUrls.add(it) }
                        Log.d(TAG, "Image uploaded successfully: ${response.body()?.image_url}")
                    } else {
                        val errorMsg = response.body()?.error ?: "Unknown error"
                        Log.e(TAG, "Image upload failed: $errorMsg, Code: ${response.code()}")
                        return@launch onComplete(false, "Ошибка загрузки изображения: $errorMsg")
                    }
                }

                val post = mapOf(
                    "user_id" to uid,
                    "title" to title,
                    "content" to content,
                    "image_urls" to if (imageUrls.isNotEmpty()) imageUrls else null,
                    "created_at" to SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(
                        Date()
                    ),
                    "is_edited" to false
                )

                Log.d(TAG, "Saving post to Firebase: $postId")
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId")
                    .setValue(post)
                    .await()

                Log.d(TAG, "Post created successfully: $postId")
                onComplete(true, "Пост создан успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Error creating post: ${e.message}", e)
                onComplete(false, "Ошибка создания поста: ${e.message}")
            }
        }
    }

    fun editPost(postId: String, title: String?, content: String?, newImageUris: List<Uri>, existingImageUrls: List<String>, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Editing post: $postId, title: $title, content: $content, newImageUris: ${newImageUris.size}, existingImageUrls: ${existingImageUrls.size}")
                if (newImageUris.size + existingImageUrls.size > 5) {
                    return@launch onComplete(false, "Максимум 5 изображений").also {
                        Log.e(TAG, "Too many images: new=${newImageUris.size}, existing=${existingImageUrls.size}")
                    }
                }
                val updates = mutableMapOf<String, Any?>()
                title?.takeIf { it.isNotBlank() }?.let { updates["title"] = it }
                content?.takeIf { it.isNotBlank() }?.let { updates["content"] = it }
                updates["is_edited"] = true

                val imageUrls = existingImageUrls.toMutableList()
                for (uri in newImageUris) {
                    val file = uriToFile(context, uri)
                    if (file == null) {
                        Log.e(TAG, "Failed to convert Uri to File: $uri")
                        return@launch onComplete(false, "Ошибка обработки изображения")
                    }
                    val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                    val imagePart = MultipartBody.Part.createFormData("image", file.name, requestFile)
                    val userId = uid.toRequestBody("text/plain".toMediaTypeOrNull())
                    val type = "post_image".toRequestBody("text/plain".toMediaTypeOrNull())

                    Log.d(TAG, "Uploading new image for post: $postId, uri: $uri")
                    val response = apiService.uploadImage(imagePart, userId, type)
                    if (response.isSuccessful && response.body()?.success == true) {
                        response.body()?.image_url?.let { imageUrls.add(it) }
                        Log.d(TAG, "Image uploaded successfully: ${response.body()?.image_url}")
                    } else {
                        val errorMsg = response.body()?.error ?: "Unknown error"
                        Log.e(TAG, "Image upload failed: $errorMsg, Code: ${response.code()}")
                        return@launch onComplete(false, "Ошибка загрузки изображения: $errorMsg")
                    }
                }
                if (imageUrls.isNotEmpty()) {
                    updates["image_urls"] = imageUrls
                } else {
                    updates["image_urls"] = null
                }

                if (updates.isNotEmpty()) {
                    Log.d(TAG, "Updating post in Firebase: $postId, updates: $updates")
                    FirebaseDatabase.getInstance()
                        .getReference("posts/$postId")
                        .updateChildren(updates)
                        .await()
                    Log.d(TAG, "Post updated successfully: $postId")
                    onComplete(true, "Пост изменён успешно")
                } else {
                    Log.d(TAG, "No changes to update for post: $postId")
                    onComplete(true, "Изменений не внесено")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error updating post: ${e.message}", e)
                onComplete(false, "Ошибка изменения поста: ${e.message}")
            }
        }
    }

    fun deletePost(postId: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Deleting post: $postId")
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId")
                    .removeValue()
                    .await()
                Log.d(TAG, "Post deleted successfully: $postId")
                onComplete(true, "Пост удалён успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Error deleting post: ${e.message}", e)
                onComplete(false, "Ошибка удаления поста: ${e.message}")
            }
        }
    }

    fun addComment(postId: String, userId: String, content: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                val commentId = FirebaseDatabase.getInstance().getReference("posts/$postId/comments").push().key
                    ?: return@launch onComplete(false, "Ошибка создания ID комментария")
                val comment = mapOf(
                    "user_id" to userId,
                    "content" to content,
                    "created_at" to SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(
                        Date()
                    )
                )
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId/comments/$commentId")
                    .setValue(comment)
                    .await()
                onComplete(true, "Комментарий добавлен успешно")
            } catch (e: Exception) {
                onComplete(false, "Ошибка добавления комментария: ${e.message}")
            }
        }
    }

    fun deleteComment(postId: String, commentId: String, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId/comments/$commentId")
                    .removeValue()
                    .await()
                onComplete(true, "Комментарий удалён успешно")
            } catch (e: Exception) {
                onComplete(false, "Ошибка удаления комментария: ${e.message}")
            }
        }
    }

    private fun setupRealtimeListeners() {
        postsListener?.let {
            FirebaseDatabase.getInstance().getReference("posts").removeEventListener(it)
        }
        postsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadPostsData()
            }

            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(error = "Ошибка слушателя постов: ${error.message}")
                Log.e(TAG, "Posts listener error: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("posts").addValueEventListener(postsListener!!)

        friendsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/friends").removeEventListener(it)
        }
        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                loadPostsData()
            }

            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(error = "Ошибка слушателя друзей: ${error.message}")
                Log.e(TAG, "Friends listener error: ${error.message}")
            }
        }
        FirebaseDatabase.getInstance().getReference("users/$uid/friends").addValueEventListener(friendsListener!!)
    }

    override fun onCleared() {
        postsListener?.let {
            FirebaseDatabase.getInstance().getReference("posts").removeEventListener(it)
        }
        friendsListener?.let {
            FirebaseDatabase.getInstance().getReference("users/$uid/friends").removeEventListener(it)
        }
        super.onCleared()
    }
}

class PostsViewModelFactory(private val uid: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(PostsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return PostsViewModel(uid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}