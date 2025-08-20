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
import java.util.UUID
import java.util.regex.Pattern

data class PostsState(
    val isLoading: Boolean = true,
    val posts: List<PostItem> = emptyList(),
    val friends: List<String> = emptyList(),
    val error: String? = null,
    val hasMorePosts: Boolean = false, // Флаг для проверки наличия дополнительных постов
    val isLoadingMore: Boolean = false, // Флаг для отображения прогресс-бара
    val lastPostTimestamp: String? = null // Для пагинации
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
    val likes: Map<String, String?> = emptyMap(),
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
                Log.d(TAG, "Loading initial posts data for user: $uid")
                val friendsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid/friends")
                        .get()
                        .await()
                }
                val friendsList = friendsSnapshot.children
                    .filter { it.child("is_friend").getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }

                val postsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("posts")
                        .orderByChild("created_at")
                        .limitToLast(2) // Загружаем 2 самых новых поста
                        .get()
                        .await()
                }
                val postsList = mutableListOf<PostItem>()
                postsSnapshot.children.reversed().forEach { child -> // Реверс для новых сверху
                    val map = child.value as? Map<String, Any> ?: return@forEach
                    val post = processPostSnapshot(child, map)
                    if (post != null && !postsList.any { it.id == post.id }) {
                        postsList.add(post)
                    }
                }

                _state.value = PostsState(
                    isLoading = false,
                    posts = postsList,
                    friends = friendsList,
                    hasMorePosts = postsSnapshot.childrenCount >= 2,
                    lastPostTimestamp = postsList.lastOrNull()?.created_at
                )
                Log.d(TAG, "Initial posts loaded: ${postsList.size}, Friends: ${friendsList.size}")
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

    fun loadRemainingPosts() {
        viewModelScope.launch {
            if (!_state.value.hasMorePosts || _state.value.isLoadingMore) return@launch
            _state.value = _state.value.copy(isLoadingMore = true)
            try {
                val lastTimestamp = _state.value.lastPostTimestamp ?: return@launch
                val existingPostIds = _state.value.posts.mapNotNull { it.id }.toSet() // Собираем ID уже загруженных постов
                val postsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("posts")
                        .orderByChild("created_at")
                        .endBefore(lastTimestamp) // Посты старше последнего загруженного
                        .limitToLast(5) // Порция из 5 постов
                        .get()
                        .await()
                }
                val additionalPosts = mutableListOf<PostItem>()
                postsSnapshot.children.reversed().forEach { child ->
                    val map = child.value as? Map<String, Any> ?: return@forEach
                    val post = processPostSnapshot(child, map)
                    if (post != null && !existingPostIds.contains(post.id)) {
                        additionalPosts.add(post)
                    }
                }
                val updatedPosts = (_state.value.posts + additionalPosts).sortedByDescending { it.created_at }
                _state.value = _state.value.copy(
                    posts = updatedPosts,
                    isLoadingMore = false,
                    hasMorePosts = postsSnapshot.childrenCount >= 5,
                    lastPostTimestamp = additionalPosts.lastOrNull()?.created_at ?: _state.value.lastPostTimestamp
                )
                Log.d(TAG, "Remaining posts loaded: ${additionalPosts.size}")
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoadingMore = false,
                    error = "Ошибка загрузки дополнительных постов: ${e.message}"
                )
                Log.e(TAG, "Error loading remaining posts: ${e.message}", e)
            }
        }
    }

    private suspend fun processPostSnapshot(child: DataSnapshot, map: Map<String, Any>): PostItem? {
        return try {
            val userSnapshot = withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance()
                    .getReference("users/${map["user_id"]}")
                    .get()
                    .await()
            }
            val userData = userSnapshot.value as? Map<String, Any>
            val imageUrls = (map["image_urls"] as? List<*>)?.mapNotNull { it as? String }
            val commentsSnapshot = withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance()
                    .getReference("posts/${child.key}/comments")
                    .get()
                    .await()
            }
            val comments = commentsSnapshot.children.mapNotNull { commentChild ->
                val commentMap = commentChild.value as? Map<String, Any>
                commentMap?.let {
                    val commentUserSnapshot = withContext(Dispatchers.IO) {
                        FirebaseDatabase.getInstance()
                            .getReference("users/${it["user_id"]}")
                            .get()
                            .await()
                    }
                    val commentUserData = commentUserSnapshot.value as? Map<String, Any>
                    CommentItem(
                        id = commentChild.key ?: "",
                        user_id = it["user_id"] as? String ?: "",
                        username = commentUserData?.get("username") as? String,
                        nickname = commentUserData?.get("nickname") as? String,
                        profile_photo = commentUserData?.get("profile_photo") as? String,
                        content = it["content"] as? String ?: "",
                        created_at = it["created_at"] as? String ?: ""
                    )
                }
            }
            val reactionsSnapshot = withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance()
                    .getReference("posts/${child.key}/reactions")
                    .get()
                    .await()
            }
            val reactions = reactionsSnapshot.children.associate { it.key!! to it.getValue(String::class.java) }
            val userReaction = reactions[uid]
            PostItem(
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
        } catch (e: Exception) {
            Log.e(TAG, "Error processing post snapshot: ${e.message}", e)
            null
        }
    }

    private fun extractMentions(text: String): List<String> {
        val pattern = Pattern.compile("@[a-zA-Z0-9_]+")
        val matcher = pattern.matcher(text)
        val mentions = mutableListOf<String>()
        while (matcher.find()) {
            mentions.add(matcher.group())
        }
        return mentions
    }

    private suspend fun getUidByUsername(username: String): String? {
        return try {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("users")
                .orderByChild("username")
                .equalTo(username)
                .get()
                .await()
            snapshot.children.firstOrNull()?.key
        } catch (e: Exception) {
            Log.e(TAG, "Error getting UID for username $username: ${e.message}", e)
            null
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
                    currentReaction == reactionType -> null // Удаляем реакцию
                    else -> reactionType // Устанавливаем новую
                }

                dbRef.setValue(newReaction).await()
                Log.d("ReactionUpdate", "Reaction updated: $postId, user: $userId, type: $newReaction")

                // Обновляем только этот пост
                updatePostReactions(postId)
            } catch (e: Exception) {
                Log.e("ReactionError", "Error updating reaction: ${e.message}", e)
            }
        }
    }

    private fun updatePostReactions(postId: String) {
        viewModelScope.launch {
            try {
                val reactionsSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("posts/$postId/reactions")
                        .get()
                        .await()
                }
                val reactions = reactionsSnapshot.children.associate { it.key!! to it.getValue(String::class.java) }
                val userReaction = reactions[uid]

                val updatedPosts = _state.value.posts.map { post ->
                    if (post.id == postId) {
                        post.copy(likes = reactions, userReaction = userReaction)
                    } else {
                        post
                    }
                }
                _state.value = _state.value.copy(posts = updatedPosts)
            } catch (e: Exception) {
                Log.e(TAG, "Error updating reactions: ${e.message}", e)
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

    fun detectLinksAndApps(content: String, context: Context): Pair<List<String>, List<List<Pair<ResolveInfo, String>>>> {
        val urlPattern = Pattern.compile(
            "(https?://[\\w\\-\\.]+(:\\d+)?(/[\\w\\-\\.]*)*(\\?[\\w\\-\\.=&%]*)?(#[\\w\\-]*)?)",
            Pattern.CASE_INSENSITIVE
        )
        val matcher = urlPattern.matcher(content)
        val links = mutableListOf<String>()
        val appsPerLink = mutableListOf<List<Pair<ResolveInfo, String>>>()

        val appSchemes = mapOf(
            "org.telegram.messenger" to listOf("tg", "https://t.me", "https://telegram.me"),
            "com.google.android.youtube" to listOf("youtube", "https://www.youtube.com", "https://youtu.be"),
            "com.zhiliaoapp.musically" to listOf("tiktok", "https://www.tiktok.com"),
            "com.twitter.android" to listOf("twitter", "https://twitter.com", "https://x.com"),
            "com.instagram.android" to listOf("instagram", "https://www.instagram.com"),
            "com.facebook.katana" to listOf("fb", "https://www.facebook.com"),
            "com.exteragram.messenger" to listOf("tg", "https://t.me", "https://telegram.me")
        )

        while (matcher.find()) {
            val url = matcher.group()
            links.add(url)
            Log.d(TAG, "Detected URL: $url")

            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: ""
            val host = uri.host?.lowercase() ?: ""

            val appsWithLabels = mutableListOf<Pair<ResolveInfo, String>>()

            appSchemes.forEach { (packageName, schemes) ->
                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                    setPackage(packageName)
                }
                val resolveInfo = context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
                if (resolveInfo.isNotEmpty()) {
                    val matchesScheme = schemes.any { scheme ->
                        if (scheme.startsWith("http")) {
                            url.contains(scheme, ignoreCase = true)
                        } else {
                            scheme == uri.scheme
                        }
                    }
                    if (matchesScheme) {
                        resolveInfo.forEach { resolve ->
                            val label = resolve.loadLabel(context.packageManager).toString()
                            appsWithLabels.add(Pair(resolve, label))
                            Log.d(TAG, "App $packageName ($label) can handle URL: $url")
                        }
                    }
                }
            }

            if (appsWithLabels.isEmpty() && (scheme == "http" || scheme == "https")) {
                val genericIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                val genericApps = context.packageManager.queryIntentActivities(genericIntent, PackageManager.MATCH_ALL)
                genericApps.forEach { resolve ->
                    val packageName = resolve.activityInfo.packageName
                    if (!appSchemes.containsKey(packageName)) {
                        val label = resolve.loadLabel(context.packageManager).toString()
                        appsWithLabels.add(Pair(resolve, label))
                        Log.d(TAG, "Generic app $packageName ($label) can handle URL: $url")
                    }
                }
            }

            val uniqueApps = appsWithLabels.distinctBy { it.first.activityInfo.packageName }
            appsPerLink.add(uniqueApps)
            Log.d(TAG, "Apps for URL $url: ${uniqueApps.map { it.second }}")
        }

        return Pair(links, appsPerLink)
    }

    fun createPost(title: String, content: String, imageUris: List<Uri>, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Создание поста с заголовком: $title, содержимым: $content, изображениями: ${imageUris.size}")
                if (imageUris.size > 5) {
                    return@launch onComplete(false, "Максимум 5 изображений")
                }
                val postId = FirebaseDatabase.getInstance().getReference("posts").push().key
                    ?: return@launch onComplete(false, "Ошибка создания ID поста")
                val imageUrls = mutableListOf<String>()

                for (uri in imageUris) {
                    val file = uriToFile(context, uri)
                    if (file == null) {
                        Log.e(TAG, "Не удалось преобразовать Uri в File: $uri")
                        return@launch onComplete(false, "Ошибка обработки изображения")
                    }
                    val requestFile = file.asRequestBody("image/*".toMediaTypeOrNull())
                    val imagePart = MultipartBody.Part.createFormData("image", file.name, requestFile)
                    val userId = uid.toRequestBody("text/plain".toMediaTypeOrNull())
                    val type = "post_image".toRequestBody("text/plain".toMediaTypeOrNull())

                    Log.d(TAG, "Загрузка изображения для поста: $postId, uri: $uri")
                    val response = apiService.uploadImage(imagePart, userId, type)
                    if (response.isSuccessful && response.body()?.success == true) {
                        response.body()?.image_url?.let { imageUrls.add(it) }
                        Log.d(TAG, "Изображение загружено успешно: ${response.body()?.image_url}")
                    } else {
                        val errorMsg = response.body()?.error ?: "Неизвестная ошибка"
                        Log.e(TAG, "Загрузка изображения не удалась: $errorMsg, Код: ${response.code()}")
                        return@launch onComplete(false, errorMsg)
                    }
                }

                val post = mapOf(
                    "user_id" to uid,
                    "title" to title,
                    "content" to content,
                    "image_urls" to if (imageUrls.isNotEmpty()) imageUrls else null,
                    "created_at" to SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
                    "is_edited" to false
                )

                Log.d(TAG, "Сохранение поста в Firebase: $postId")
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId")
                    .setValue(post)
                    .await()

                // Отправляем уведомления об упоминаниях
                val mentions = extractMentions(content)
                for (mention in mentions) {
                    val mentionedUid = getUidByUsername(mention.removePrefix("@"))
                    if (mentionedUid != null && mentionedUid != uid) {
                        val notificationId = UUID.randomUUID().toString()
                        FirebaseDatabase.getInstance()
                            .getReference("users/$mentionedUid/notifications/$notificationId")
                            .setValue(
                                mapOf(
                                    "type" to "mention",
                                    "from_uid" to uid,
                                    "post_id" to postId,
                                    "timestamp" to System.currentTimeMillis(),
                                    "message" to "$mention, вас упомянули в посте"
                                )
                            ).await()
                        Log.d(TAG, "Уведомление отправлено для $mention (uid: $mentionedUid)")
                    }
                }

                val userSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid")
                        .get()
                        .await()
                }
                val userData = userSnapshot.value as? Map<String, Any>
                val newPost = PostItem(
                    id = postId,
                    user_id = uid,
                    username = userData?.get("username") as? String,
                    nickname = userData?.get("nickname") as? String,
                    profile_photo = userData?.get("profile_photo") as? String,
                    title = title,
                    content = content,
                    image_urls = if (imageUrls.isNotEmpty()) imageUrls else null,
                    created_at = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
                    is_edited = false,
                    comments = emptyList(),
                    likes = emptyMap(),
                    userReaction = null
                )
                _state.value = _state.value.copy(posts = (_state.value.posts + newPost).sortedByDescending { it.created_at })

                Log.d(TAG, "Пост создан успешно: $postId")
                onComplete(true, "Пост создан успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка создания поста: ${e.message}", e)
                onComplete(false, "Ошибка создания поста: ${e.message}")
            }
        }
    }

    fun editPost(postId: String, title: String?, content: String?, newImageUris: List<Uri>, existingImageUrls: List<String>, context: Context, onComplete: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            try {
                Log.d(TAG, "Editing, title: $title, content: $content, newImageUris: ${newImageUris.size}, existingImageUrls: ${existingImageUrls.size}")
                if (newImageUris.size + existingImageUrls.size > 5) {
                    return@launch onComplete(false, "Максимум 5 изображений")
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
                        return@launch onComplete(false, errorMsg)
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

                    // Отправляем уведомления об упоминаниях, если содержимое обновлено
                    content?.let { newContent ->
                        val mentions = extractMentions(newContent)
                        for (mention in mentions) {
                            val mentionedUid = getUidByUsername(mention.removePrefix("@"))
                            if (mentionedUid != null && mentionedUid != uid) {
                                val notificationId = UUID.randomUUID().toString()
                                FirebaseDatabase.getInstance()
                                    .getReference("users/$mentionedUid/notifications/$notificationId")
                                    .setValue(
                                        mapOf(
                                            "type" to "mention",
                                            "from_uid" to uid,
                                            "post_id" to postId,
                                            "timestamp" to System.currentTimeMillis(),
                                            "message" to "$mention, вас упомянули в отредактированном посте"
                                        )
                                    ).await()
                                Log.d(TAG, "Уведомление отправлено для $mention (uid: $mentionedUid)")
                            }
                        }
                    }

                    val updatedPosts = _state.value.posts.map { post ->
                        if (post.id == postId) {
                            post.copy(
                                title = title ?: post.title,
                                content = content ?: post.content,
                                image_urls = if (imageUrls.isNotEmpty()) imageUrls else null,
                                is_edited = true
                            )
                        } else {
                            post
                        }
                    }
                    _state.value = _state.value.copy(posts = updatedPosts)

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

                // Удаляем пост из состояния
                val updatedPosts = _state.value.posts.filter { it.id != postId }
                _state.value = _state.value.copy(posts = updatedPosts)

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
                val createdAt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date())
                val comment = mapOf(
                    "user_id" to userId,
                    "content" to content,
                    "created_at" to createdAt
                )
                FirebaseDatabase.getInstance()
                    .getReference("posts/$postId/comments/$commentId")
                    .setValue(comment)
                    .await()

                // Отправляем уведомления об упоминаниях
                val mentions = extractMentions(content)
                for (mention in mentions) {
                    val mentionedUid = getUidByUsername(mention.removePrefix("@"))
                    if (mentionedUid != null && mentionedUid != userId) {
                        val notificationId = UUID.randomUUID().toString()
                        FirebaseDatabase.getInstance()
                            .getReference("users/$mentionedUid/notifications/$notificationId")
                            .setValue(
                                mapOf(
                                    "type" to "mention",
                                    "from_uid" to userId,
                                    "post_id" to postId,
                                    "comment_id" to commentId,
                                    "timestamp" to System.currentTimeMillis(),
                                    "message" to "$mention, вас упомянули в комментарии"
                                )
                            ).await()
                        Log.d(TAG, "Уведомление отправлено для $mention (uid: $mentionedUid)")
                    }
                }

                // Обновляем пост в состоянии
                val userSnapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$userId")
                        .get()
                        .await()
                }
                val userData = userSnapshot.value as? Map<String, Any>
                val newComment = CommentItem(
                    id = commentId,
                    user_id = userId,
                    username = userData?.get("username") as? String,
                    nickname = userData?.get("nickname") as? String,
                    profile_photo = userData?.get("profile_photo") as? String,
                    content = content,
                    created_at = createdAt
                )
                val updatedPosts = _state.value.posts.map { post ->
                    if (post.id == postId) {
                        post.copy(comments = post.comments + newComment)
                    } else {
                        post
                    }
                }
                _state.value = _state.value.copy(posts = updatedPosts)

                onComplete(true, "Комментарий добавлен успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Error adding comment: ${e.message}", e)
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

                // Обновляем пост в состоянии
                val updatedPosts = _state.value.posts.map { post ->
                    if (post.id == postId) {
                        post.copy(comments = post.comments.filter { it.id != commentId })
                    } else {
                        post
                    }
                }
                _state.value = _state.value.copy(posts = updatedPosts)

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
                // Не перезагружаем все данные, а обновляем только изменённые посты
                viewModelScope.launch {
                    try {
                        val currentPosts = _state.value.posts.associateBy { it.id }
                        val updatedPosts = mutableListOf<PostItem>()
                        snapshot.children.forEach { child ->
                            val map = child.value as? Map<String, Any> ?: return@forEach
                            val postId = child.key ?: return@forEach
                            val existingPost = currentPosts[postId]
                            if (existingPost != null) {
                                val reactionsSnapshot = withContext(Dispatchers.IO) {
                                    FirebaseDatabase.getInstance()
                                        .getReference("posts/$postId/reactions")
                                        .get()
                                        .await()
                                }
                                val reactions = reactionsSnapshot.children.associate { it.key!! to it.getValue(String::class.java) }
                                val userReaction = reactions[uid]
                                val commentsSnapshot = withContext(Dispatchers.IO) {
                                    FirebaseDatabase.getInstance()
                                        .getReference("posts/$postId/comments")
                                        .get()
                                        .await()
                                }
                                val comments = commentsSnapshot.children.mapNotNull { commentChild ->
                                    val commentMap = commentChild.value as? Map<String, Any>
                                    commentMap?.let {
                                        val commentUserSnapshot = withContext(Dispatchers.IO) {
                                            FirebaseDatabase.getInstance()
                                                .getReference("users/${it["user_id"]}")
                                                .get()
                                                .await()
                                        }
                                        val commentUserData = commentUserSnapshot.value as? Map<String, Any>
                                        CommentItem(
                                            id = commentChild.key ?: "",
                                            user_id = it["user_id"] as? String ?: "",
                                            username = commentUserData?.get("username") as? String,
                                            nickname = commentUserData?.get("nickname") as? String,
                                            profile_photo = commentUserData?.get("profile_photo") as? String,
                                            content = it["content"] as? String ?: "",
                                            created_at = it["created_at"] as? String ?: ""
                                        )
                                    }
                                }
                                updatedPosts.add(
                                    existingPost.copy(
                                        comments = comments,
                                        likes = reactions,
                                        userReaction = userReaction
                                    )
                                )
                            }
                        }
                        _state.value = _state.value.copy(posts = updatedPosts.sortedByDescending { it.created_at })
                    } catch (e: Exception) {
                        Log.e(TAG, "Error in posts listener: ${e.message}", e)
                    }
                }
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
                val friendsList = snapshot.children
                    .filter { it.child("is_friend").getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }
                _state.value = _state.value.copy(friends = friendsList)
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