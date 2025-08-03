package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.ui.dialogs.DialogController
import com.SkrinVex.OfoxMessenger.ui.dialogs.GlobalDialogHost
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.ui.viewer.PhotoViewerActivity
import com.google.firebase.database.*
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
import java.util.*
import androidx.compose.ui.graphics.Brush

class PostsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getStringExtra("uid") ?: ""
        val viewModel: PostsViewModel by viewModels {
            PostsViewModelFactory(uid)
        }

        setContent {
            OfoxMessengerTheme {
                PostsScreen(viewModel = viewModel)
                GlobalDialogHost()
            }
        }
    }
}

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
    val is_edited: Boolean = false
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
                            is_edited = map["is_edited"] as? Boolean ?: false
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
                    "created_at" to SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date()),
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

@Composable
fun PostsScreen(viewModel: PostsViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf("all") } // "all" или "subscriptions"
    var showCreatePostDialog by remember { mutableStateOf(false) }
    var createPostState by remember { mutableStateOf(CreatePostState()) }

    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (uris != null && uris.size <= 5) {
            createPostState = createPostState.copy(imageUris = uris)
        } else if (uris != null) {
            Toast.makeText(context, "Максимум 5 изображений", Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        containerColor = Color(0xFF101010),
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showCreatePostDialog = true },
                containerColor = Color(0xFFFF6B35),
                contentColor = Color.Black,
                shape = CircleShape,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Add,
                    contentDescription = "Создать пост",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    ) { paddingValues ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF6B35))
            }
        } else if (state.posts.isNotEmpty() || selectedTab == "subscriptions") {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    ModernToggle(
                        selectedTab = selectedTab,
                        onTabSelected = { tab ->
                            selectedTab = tab
                            if (tab == "subscriptions") {
                                DialogController.showComingSoonDialog()
                            }
                        },
                        hasNotifications = false,
                        tab1Text = "Общая лента",
                        tab1Value = "all",
                        tab2Text = "Подписки",
                        tab2Value = "subscriptions"
                    )
                }

                val filteredPosts = if (selectedTab == "all") {
                    state.posts
                } else {
                    state.posts.filter { state.friends.contains(it.user_id) }
                }

                if (filteredPosts.isNotEmpty()) {
                    items(filteredPosts) { post ->
                        PostCard(
                            post = post,
                            currentUid = (context as? PostsActivity)?.intent?.getStringExtra("uid") ?: "",
                            viewModel = viewModel
                        )
                    }
                } else {
                    item {
                        Text(
                            text = if (selectedTab == "all") "Нет постов" else "Нет постов от подписок",
                            color = Color.White,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(text = state.error ?: "Нет постов", color = Color.White)
            }
        }
    }

    if (showCreatePostDialog) {
        CreatePostDialog(
            state = createPostState,
            onStateChange = { createPostState = it },
            onPickImages = { pickImages.launch("image/*") },
            onCreatePost = {
                viewModel.createPost(
                    title = createPostState.title,
                    content = createPostState.content,
                    imageUris = createPostState.imageUris,
                    context = context
                ) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) {
                        createPostState = CreatePostState()
                        showCreatePostDialog = false
                    }
                }
            },
            onDismiss = {
                createPostState = CreatePostState()
                showCreatePostDialog = false
            }
        )
    }
}

data class CreatePostState(
    val title: String = "",
    val content: String = "",
    val imageUris: List<Uri> = emptyList(),
    val existingImageUrls: List<String> = emptyList(),
    val isUploading: Boolean = false
)

@Composable
fun CreatePostDialog(
    state: CreatePostState,
    onStateChange: (CreatePostState) -> Unit,
    onPickImages: () -> Unit,
    onCreatePost: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Создать пост",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { onStateChange(state.copy(title = it)) },
                    label = { Text("Заголовок", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.Gray
                    )
                )
                OutlinedTextField(
                    value = state.content,
                    onValueChange = { onStateChange(state.copy(content = it)) },
                    label = { Text("Описание", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Button(
                    onClick = onPickImages,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Добавить изображения (макс. 5)", fontSize = 14.sp)
                }
                if (state.imageUris.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.imageUris.forEachIndexed { index, uri ->
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onStateChange(state.copy(imageUris = state.imageUris.filterIndexed { i, _ -> i != index }))
                                    }
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Выбранное изображение ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Удалить изображение",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF333333),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Отмена", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onStateChange(state.copy(isUploading = true))
                            onCreatePost()
                        },
                        enabled = state.title.isNotBlank() && state.content.isNotBlank() && !state.isUploading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Создать", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPostDialog(
    post: PostItem,
    state: CreatePostState,
    onStateChange: (CreatePostState) -> Unit,
    onPickImages: () -> Unit,
    onEditPost: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Изменить пост",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                OutlinedTextField(
                    value = state.title,
                    onValueChange = { onStateChange(state.copy(title = it)) },
                    label = { Text("Заголовок", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.Gray
                    )
                )
                OutlinedTextField(
                    value = state.content,
                    onValueChange = { onStateChange(state.copy(content = it)) },
                    label = { Text("Описание", color = Color.White) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color(0xFFFF6B35),
                        unfocusedBorderColor = Color.Gray
                    )
                )
                Button(
                    onClick = onPickImages,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Добавить изображения (макс. 5)", fontSize = 14.sp)
                }
                if (state.imageUris.isNotEmpty() || post.image_urls?.isNotEmpty() == true) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        post.image_urls?.forEachIndexed { index, url ->
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onStateChange(state.copy(existingImageUrls = state.existingImageUrls.filterIndexed { i, _ -> i != index }))
                                    }
                            ) {
                                AsyncImage(
                                    model = "https://api.skrinvex.su$url",
                                    contentDescription = "Существующее изображение ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Удалить изображение",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .padding(4.dp)
                                )
                            }
                        }
                        state.imageUris.forEachIndexed { index, uri ->
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onStateChange(state.copy(imageUris = state.imageUris.filterIndexed { i, _ -> i != index }))
                                    }
                            ) {
                                AsyncImage(
                                    model = uri,
                                    contentDescription = "Новое изображение ${index + 1}",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Icon(
                                    imageVector = Icons.Rounded.Delete,
                                    contentDescription = "Удалить изображение",
                                    tint = Color.White,
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .size(24.dp)
                                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                        .padding(4.dp)
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF333333),
                            contentColor = Color.White
                        )
                    ) {
                        Text("Отмена", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            onStateChange(state.copy(isUploading = true))
                            onEditPost()
                        },
                        enabled = (state.title.isNotBlank() || state.content.isNotBlank() || state.imageUris.isNotEmpty() || state.existingImageUrls.isNotEmpty()) && !state.isUploading,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (state.isUploading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Сохранить", fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DeleteConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Удалить пост", color = Color.White, fontWeight = FontWeight.Bold) },
        text = { Text("Вы уверены, что хотите удалить этот пост?", color = Color.White) },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35))
            ) {
                Text("Удалить", color = Color.Black)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = Color(0xFF333333),
                    contentColor = Color.White
                )
            ) {
                Text("Отмена", fontWeight = FontWeight.Bold)
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCard(post: PostItem, currentUid: String, viewModel: PostsViewModel) {
    val context = LocalContext.current
    var isImageLoading by remember { mutableStateOf(true) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var editPostState by remember {
        mutableStateOf(
            CreatePostState(
                title = post.title,
                content = post.content,
                imageUris = emptyList(),
                existingImageUrls = post.image_urls ?: emptyList()
            )
        )
    }

    Card(
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E),
            contentColor = Color.White
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // ——— Автор поста ———
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable {
                    post.user_id?.let { userId ->
                        val intent = Intent(context, ProfileViewActivity::class.java).apply {
                            putExtra("uid", currentUid)
                            if (userId != currentUid) {
                                putExtra("friend_uid", userId)
                                putExtra("notificationId", null as String?)
                            }
                        }
                        context.startActivity(intent)
                    }
                }
            ) {
                AsyncImage(
                    model = post.profile_photo?.takeIf { it.isNotBlank() }?.let { "https://api.skrinvex.su$it" },
                    contentDescription = "Аватар",
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333)),
                    contentScale = ContentScale.Crop
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = post.nickname ?: post.username ?: "Пользователь",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ——— Заголовок и кнопка ———
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = post.title,
                    style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFFFF6B35)),
                    modifier = Modifier.weight(1f)
                )
                if (post.is_edited) {
                    Text(
                        text = "Изменено",
                        color = Color.Gray,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                IconButton(onClick = { showBottomSheet = true }) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = "Опции поста",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Дата: ${post.created_at}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = post.content,
                style = MaterialTheme.typography.bodyMedium
            )

            post.image_urls?.takeIf { it.isNotEmpty() }?.let { urls ->
                Spacer(modifier = Modifier.height(10.dp))
                val pagerState = rememberPagerState(pageCount = { urls.size })
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                ) {
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.fillMaxSize(),
                        pageSpacing = 8.dp
                    ) { page ->
                        AsyncImage(
                            model = "https://api.skrinvex.su${urls[page]}",
                            contentDescription = "Изображение поста ${page + 1}",
                            contentScale = ContentScale.Crop,
                            onLoading = { isImageLoading = true },
                            onSuccess = { isImageLoading = false },
                            onError = { isImageLoading = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    PhotoViewerActivity.start(context, "https://api.skrinvex.su${urls[page]}")
                                }
                        )
                    }
                    if (isImageLoading) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = Color(0xFFFF6B35),
                                strokeWidth = 4.dp
                            )
                        }
                    }
                    if (urls.size > 1) {
                        PagerIndicator(
                            pageCount = urls.size,
                            currentPage = pagerState.currentPage,
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 8.dp)
                        )
                    }
                }
            }
        }
    }

    // ——— Нижнее меню опций ———
    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            containerColor = Color(0xFF1E1E1E).copy(alpha = 0.95f),
            contentColor = Color.White,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            tonalElevation = 8.dp,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color(0xFFFF6B35).copy(alpha = 0.2f),
                                    Color(0xFF1E1E1E).copy(alpha = 0.95f)
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 12.dp)
                            .width(36.dp)
                            .height(4.dp)
                            .background(
                                color = Color(0xFFFF6B35).copy(alpha = 0.6f),
                                shape = RoundedCornerShape(50)
                            )
                    )
                }
            }
        ) {
            BottomSheetContent(
                item = post,
                isOwnItem = post.user_id == currentUid,
                onEdit = { showEditDialog = true },
                onDelete = { showDeleteDialog = true },
                onShare = {
                    DialogController.showComingSoonDialog()
                }
            )
        }
    }

    // ——— Диалог редактирования ———
    if (showEditDialog) {
        val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
            if (uris != null && uris.size + editPostState.existingImageUrls.size <= 5) {
                editPostState = editPostState.copy(imageUris = uris)
            } else if (uris != null) {
                Toast.makeText(context, "Максимум 5 изображений", Toast.LENGTH_SHORT).show()
            }
        }
        EditPostDialog(
            post = post,
            state = editPostState,
            onStateChange = { editPostState = it },
            onPickImages = { pickImages.launch("image/*") },
            onEditPost = {
                post.id?.let { postId ->
                    viewModel.editPost(
                        postId = postId,
                        title = editPostState.title.takeIf { it.isNotBlank() },
                        content = editPostState.content.takeIf { it.isNotBlank() },
                        newImageUris = editPostState.imageUris,
                        existingImageUrls = editPostState.existingImageUrls,
                        context = context
                    ) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        if (success) {
                            editPostState = CreatePostState(
                                title = post.title,
                                content = post.content,
                                imageUris = emptyList(),
                                existingImageUrls = post.image_urls ?: emptyList()
                            )
                            showEditDialog = false
                        }
                    }
                }
            },
            onDismiss = {
                editPostState = CreatePostState(
                    title = post.title,
                    content = post.content,
                    imageUris = emptyList(),
                    existingImageUrls = post.image_urls ?: emptyList()
                )
                showEditDialog = false
            }
        )
    }

    // ——— Диалог удаления ———
    if (showDeleteDialog) {
        DeleteConfirmationDialog(
            itemType = "пост",
            onConfirm = {
                post.id?.let { postId ->
                    viewModel.deletePost(postId) { success, message ->
                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                        if (success) showDeleteDialog = false
                    }
                }
            },
            onDismiss = { showDeleteDialog = false }
        )
    }
}

@Composable
fun PagerIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center
    ) {
        repeat(pageCount) { index ->
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .padding(2.dp)
                    .clip(CircleShape)
                    .background(if (index == currentPage) Color(0xFFFF6B35) else Color.Gray)
            )
        }
    }
}