package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.SkrinVex.OfoxMessenger.ui.dialogs.DialogController
import com.SkrinVex.OfoxMessenger.ui.dialogs.GlobalDialogHost
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.ui.viewer.PhotoViewerActivity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.core.graphics.drawable.toBitmap
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import kotlinx.coroutines.delay
import java.util.regex.Pattern

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

@Composable
fun PostsScreen(viewModel: PostsViewModel) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    var selectedTab by remember { mutableStateOf("all") } // "all" или "friends"
    var showCreatePostDialog by remember { mutableStateOf(false) }
    var createPostState by remember { mutableStateOf(CreatePostState()) }
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }

    // Анимация для поисковой строки
    val searchBarAlpha by animateFloatAsState(
        targetValue = if (isSearchActive) 1f else 0f,
        animationSpec = tween(durationMillis = 300)
    )
    val searchBarScale by animateFloatAsState(
        targetValue = if (isSearchActive) 1f else 0.8f,
        animationSpec = tween(durationMillis = 300)
    )

    // Лаунчер для выбора изображений
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris: List<Uri>? ->
        if (uris != null && uris.size <= 5) {
            createPostState = createPostState.copy(imageUris = uris)
        } else if (uris != null) {
            Toast.makeText(context, "Максимум 5 изображений", Toast.LENGTH_SHORT).show()
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
                    if (success) {
                        createPostState = CreatePostState()
                        showCreatePostDialog = false
                        Toast.makeText(context, "Пост создан успешно", Toast.LENGTH_SHORT).show()
                    } else {
                        createPostState = createPostState.copy(
                            isUploading = false,
                            errorMessage = message
                        )
                    }
                }
            },
            onDismiss = {
                createPostState = CreatePostState()
                showCreatePostDialog = false
            }
        )
    }

    // Состояние прокрутки
    val lazyListState = rememberLazyListState()

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
        },
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.statusBars) // Отступ от статус-бара
                    .background(Color(0xFF101010))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isSearchActive) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Поиск постов...", color = Color.Gray, fontSize = 14.sp) },
                        modifier = Modifier
                            .weight(1f)
                            .alpha(searchBarAlpha)
                            .scale(searchBarScale)
                            .clip(RoundedCornerShape(24.dp))
                            .background(Color(0xFF2A2A2A), RoundedCornerShape(24.dp)),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = Color(0xFFFF6B35),
                            unfocusedBorderColor = Color(0xFF333333),
                            cursorColor = Color(0xFFFF6B35)
                        ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = "Поиск",
                                tint = Color.White
                            )
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { searchQuery = "" }) {
                                    Icon(
                                        imageVector = Icons.Rounded.Clear,
                                        contentDescription = "Очистить",
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    )
                } else {
                    Text(
                        text = "Лента",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f)
                    )
                }
                IconButton(
                    onClick = { isSearchActive = !isSearchActive },
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2A2A2A))
                ) {
                    Icon(
                        imageVector = if (isSearchActive) Icons.Rounded.Close else Icons.Rounded.Search,
                        contentDescription = if (isSearchActive) "Закрыть поиск" else "Открыть поиск",
                        tint = Color(0xFFFF6B35)
                    )
                }
            }
        }
    ) { paddingValues ->
        if (state.isLoading && state.posts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF6B35))
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
            ) {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        ModernToggle(
                            selectedTab = selectedTab,
                            onTabSelected = { tab ->
                                selectedTab = tab
                            },
                            hasNotifications = false,
                            tab1Text = "Общая лента",
                            tab1Value = "all",
                            tab2Text = "Друзья",
                            tab2Value = "friends"
                        )
                    }

                    val filteredPosts = if (selectedTab == "all") {
                        state.posts.filter { post ->
                            post.title.contains(searchQuery, ignoreCase = true) ||
                                    post.content.contains(searchQuery, ignoreCase = true)
                        }
                    } else {
                        state.posts.filter { post ->
                            state.friends.contains(post.user_id) &&
                                    (post.title.contains(searchQuery, ignoreCase = true) ||
                                            post.content.contains(searchQuery, ignoreCase = true))
                        }
                    }

                    if (filteredPosts.isNotEmpty()) {
                        items(
                            items = filteredPosts,
                            key = { post -> post.id ?: "" }
                        ) { post ->
                            PostCard(
                                post = post,
                                currentUid = (context as? PostsActivity)?.intent?.getStringExtra("uid") ?: "",
                                viewModel = viewModel
                            )
                        }
                    } else {
                        item {
                            Text(
                                text = if (selectedTab == "all") "Нет постов" else "Нет постов от друзей",
                                color = Color.White,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }

                    if (state.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    color = Color(0xFFFF6B35),
                                    strokeWidth = 4.dp
                                )
                            }
                        }
                    }
                }

                // Подгрузка постов при прокрутке до конца
                LaunchedEffect(lazyListState) {
                    snapshotFlow { lazyListState.layoutInfo }
                        .collect { layoutInfo ->
                            val lastVisibleItem = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                            val totalItems = layoutInfo.totalItemsCount
                            if (lastVisibleItem >= totalItems - 1 && state.hasMorePosts && !state.isLoadingMore) {
                                viewModel.loadRemainingPosts()
                            }
                        }
                }
            }
        }
    }
}

data class CreatePostState(
    val title: String = "",
    val content: String = "",
    val imageUris: List<Uri> = emptyList(),
    val existingImageUrls: List<String> = emptyList(),
    val isUploading: Boolean = false,
    val errorMessage: String? = null // Добавлено для ошибок
)

@Composable
fun CreatePostDialog(
    state: CreatePostState,
    onStateChange: (CreatePostState) -> Unit,
    onPickImages: () -> Unit,
    onCreatePost: () -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = {}) {
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
                    onValueChange = { onStateChange(state.copy(title = it, errorMessage = null)) },
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
                    onValueChange = { onStateChange(state.copy(content = it, errorMessage = null)) },
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
                                        onStateChange(state.copy(imageUris = state.imageUris.filterIndexed { i, _ -> i != index }, errorMessage = null))
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
                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage,
                        color = Color.Red,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
                            onStateChange(state.copy(isUploading = true, errorMessage = null))
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
    Dialog(onDismissRequest = {}) {
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
                    onValueChange = { onStateChange(state.copy(title = it, errorMessage = null)) },
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
                    onValueChange = { onStateChange(state.copy(content = it, errorMessage = null)) },
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
                if (state.imageUris.isNotEmpty() || state.existingImageUrls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        state.existingImageUrls.forEachIndexed { index, url ->
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable {
                                        onStateChange(state.copy(existingImageUrls = state.existingImageUrls.filterIndexed { i, _ -> i != index }, errorMessage = null))
                                    }
                            ) {
                                AsyncImage(
                                    model = url,
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
                                        onStateChange(state.copy(imageUris = state.imageUris.filterIndexed { i, _ -> i != index }, errorMessage = null))
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
                if (state.errorMessage != null) {
                    Text(
                        text = state.errorMessage,
                        color = Color.Red,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
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
                            onStateChange(state.copy(isUploading = true, errorMessage = null))
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

@Composable
fun ReactionButtons(
    postId: String,
    userId: String,
    userReaction: String?,
    likesCount: Int,
    dislikesCount: Int,
    viewModel: PostsViewModel,
    isLoading: Boolean // Добавлено состояние загрузки
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Кнопка лайка
        IconButton(
            onClick = { viewModel.toggleReaction(postId, userId, "like") },
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (userReaction == "like") Color(0xFFFF6B35).copy(alpha = 0.2f) else Color.Transparent,
                    shape = CircleShape
                ),
            enabled = !isLoading // Отключаем кнопку во время загрузки
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFFFF6B35),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ThumbUp,
                        contentDescription = "Like",
                        tint = if (userReaction == "like") Color(0xFFFF6B35) else Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = likesCount.toString(),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        // Кнопка дизлайка
        IconButton(
            onClick = { viewModel.toggleReaction(postId, userId, "dislike") },
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (userReaction == "dislike") Color(0xFFFF6B35).copy(alpha = 0.2f) else Color.Transparent,
                    shape = CircleShape
                ),
            enabled = !isLoading // Отключаем кнопку во время загрузки
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color(0xFFFF6B35),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = Icons.Rounded.ThumbDown,
                        contentDescription = "Dislike",
                        tint = if (userReaction == "dislike") Color(0xFFFF6B35) else Color.White
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = dislikesCount.toString(),
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCard(post: PostItem, currentUid: String, viewModel: PostsViewModel) {
    val context = LocalContext.current
    var isImageLoading by remember { mutableStateOf(true) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    var likesCount by remember { mutableStateOf(post.likes.count { it.value == "like" }) }
    var dislikesCount by remember { mutableStateOf(post.likes.count { it.value == "dislike" }) }
    var isReactionLoading by remember { mutableStateOf(false) } // Локальная загрузка реакции
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
                        if (success) {
                            editPostState = CreatePostState(
                                title = post.title,
                                content = post.content,
                                imageUris = emptyList(),
                                existingImageUrls = post.image_urls ?: emptyList()
                            )
                            showEditDialog = false
                            Toast.makeText(context, "Пост изменён успешно", Toast.LENGTH_SHORT).show()
                        } else {
                            editPostState = editPostState.copy(
                                isUploading = false,
                                errorMessage = message
                            )
                            // Toast.makeText(context, message, Toast.LENGTH_SHORT).show() // Можно убрать
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

    // Обновляем количество реакций при изменении поста
    LaunchedEffect(post.likes) {
        likesCount = post.likes.count { it.value == "like" }
        dislikesCount = post.likes.count { it.value == "dislike" }
    }

    // Detect links and compatible apps
    val linkData = remember(post.content) {
        viewModel.detectLinksAndApps(post.content, context)
    }
    val links = linkData.first
    val appsPerLink = linkData.second

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
                    model = post.profile_photo?.takeIf { it.isNotBlank() },
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

            SmartLinkText(text = post.content)

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
                            model = urls[page],
                            contentDescription = "Изображение поста ${page + 1}",
                            contentScale = ContentScale.Crop,
                            onLoading = { isImageLoading = true },
                            onSuccess = { isImageLoading = false },
                            onError = { isImageLoading = false },
                            modifier = Modifier
                                .fillMaxSize()
                                .clickable {
                                    PhotoViewerActivity.start(context, urls[page])
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

            Spacer(modifier = Modifier.height(8.dp))

            // Реакции с локальным спиннером
            post.id?.let { postId ->
                ReactionButtons(
                    postId = postId,
                    userId = currentUid,
                    userReaction = post.userReaction,
                    likesCount = likesCount,
                    dislikesCount = dislikesCount,
                    viewModel = viewModel,
                    isLoading = isReactionLoading
                )
            }

            if (post.comments.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF2A2A2A))
                        .clickable { showCommentsSheet = true }
                        .padding(8.dp)
                ) {
                    post.comments.take(3).forEach { comment ->
                        AsyncImage(
                            model = comment.profile_photo?.takeIf { it.isNotBlank() },
                            contentDescription = "Аватар комментатора",
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF333333)),
                            contentScale = ContentScale.Crop
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = "Комментарии (${post.comments.size})",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Rounded.ArrowForward,
                        contentDescription = "Открыть комментарии",
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.size(16.dp)
                    )
                }
            } else {
                Button(
                    onClick = { showCommentsSheet = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Text("Написать комментарий", color = Color.Black, fontSize = 14.sp)
                }
            }
        }
    }

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

    if (showDeleteDialog) {
        DeleteConfirmationDialog(
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

    if (showCommentsSheet) {
        CommentsBottomSheet(
            post = post,
            currentUid = currentUid,
            viewModel = viewModel,
            onDismiss = { showCommentsSheet = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsBottomSheet(
    post: PostItem,
    currentUid: String,
    viewModel: PostsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    var commentText by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var showActionsSheet by remember { mutableStateOf<CommentItem?>(null) }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
    )

    // Focus текстового поля при первом запуске
    LaunchedEffect(Unit) {
        delay(300) // небольшая задержка для стабильности
        focusRequester.requestFocus()
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Комментарии (${post.comments.size})",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                items(post.comments) { comment ->
                    CommentItem(
                        comment = comment,
                        currentUid = currentUid,
                        onProfileClick = {
                            val intent = Intent(context, ProfileViewActivity::class.java).apply {
                                putExtra("uid", currentUid)
                                if (comment.user_id != currentUid) {
                                    putExtra("friend_uid", comment.user_id)
                                    putExtra("notificationId", null as String?)
                                }
                            }
                            context.startActivity(intent)
                        },
                        onLongPress = { showActionsSheet = comment }
                    )
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = commentText,
                    onValueChange = { commentText = it },
                    placeholder = { Text("Напишите комментарий", color = Color.Gray) },
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier
                        .weight(1f)
                        .background(Color(0xFF2A2A2A), shape = RoundedCornerShape(20.dp))
                        .padding(end = 8.dp)
                        .focusRequester(focusRequester),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Color(0xFFFF6B35)
                    ),
                    maxLines = 4
                )

                IconButton(
                    onClick = {
                        if (commentText.isNotBlank()) {
                            isLoading = true
                            keyboardController?.hide()
                            viewModel.addComment(post.id!!, currentUid, commentText) { success, message ->
                                isLoading = false
                                if (success) {
                                    commentText = ""
                                } else {
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    },
                    enabled = !isLoading
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color(0xFFFF6B35),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = "Отправить комментарий",
                            tint = Color(0xFFFF6B35)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
    }

    showActionsSheet?.let { comment ->
        CommentActionsBottomSheet(
            comment = comment,
            isOwnComment = comment.user_id == currentUid,
            onCopy = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("Comment", comment.content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Комментарий скопирован", Toast.LENGTH_SHORT).show()
                showActionsSheet = null
            },
            onDelete = {
                viewModel.deleteComment(post.id!!, comment.id) { success, message ->
                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                    if (success) showActionsSheet = null
                }
            },
            onDismiss = { showActionsSheet = null }
        )
    }
}

@Composable
fun CommentItem(
    comment: CommentItem,
    currentUid: String,
    onProfileClick: () -> Unit,
    onLongPress: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { onLongPress() })
            },
        color = Color(0xFF2A2A2A).copy(alpha = 0.95f),
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            AsyncImage(
                model = comment.profile_photo?.takeIf { it.isNotBlank() },
                contentDescription = "Аватар комментатора",
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
                    .clickable { onProfileClick() },
                contentScale = ContentScale.Crop
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = comment.nickname ?: comment.username ?: "Пользователь",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onProfileClick() }
                )
                SmartLinkText(
                    text = comment.content,
                    color = Color.White,
                    fontSize = 14.sp
                )
                Text(
                    text = comment.created_at,
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentActionsBottomSheet(
    comment: CommentItem,
    isOwnComment: Boolean,
    onCopy: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "Действия с комментарием",
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(bottom = 16.dp)
            )
            if (isOwnComment) {
                OptionButton(
                    icon = Icons.Rounded.Delete,
                    label = "Удалить",
                    onClick = onDelete
                )
            } else {
                OptionButton(
                    icon = Icons.Rounded.FileCopy,
                    label = "Копировать",
                    onClick = onCopy
                )
            }
            Spacer(modifier = Modifier.navigationBarsPadding())
        }
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