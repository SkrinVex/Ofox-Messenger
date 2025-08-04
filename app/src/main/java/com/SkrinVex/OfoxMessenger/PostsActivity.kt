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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.ripple.rememberRipple
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

@Composable
fun ReactionButtons(
    postId: String,
    userId: String,
    userReaction: String?,
    likesCount: Int,
    dislikesCount: Int,
    viewModel: PostsViewModel
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
            enabled = true // Реакции разрешены для всех постов
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

        // Кнопка дизлайка
        IconButton(
            onClick = { viewModel.toggleReaction(postId, userId, "dislike") },
            modifier = Modifier
                .size(40.dp)
                .background(
                    color = if (userReaction == "dislike") Color(0xFFFF6B35).copy(alpha = 0.2f) else Color.Transparent,
                    shape = CircleShape
                ),
            enabled = true // Реакции разрешены для всех постов
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PostCard(post: PostItem, currentUid: String, viewModel: PostsViewModel) {
    val context = LocalContext.current
    var isImageLoading by remember { mutableStateOf(true) }
    var showBottomSheet by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCommentsSheet by remember { mutableStateOf(false) }
    var likesCount by remember { mutableStateOf(0) }
    var dislikesCount by remember { mutableStateOf(0) }
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

    // Load reaction counts
    LaunchedEffect(post.id) {
        post.id?.let { postId ->
            viewModel.getReactionCounts(postId) { likes, dislikes ->
                likesCount = likes
                dislikesCount = dislikes
            }
        }
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
                // Display app selection for links
                if (links.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(start = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        appsPerLink.forEachIndexed { index, apps ->
                            if (apps.isNotEmpty()) {
                                apps.forEach { (app, label) ->
                                    val icon = app.loadIcon(context.packageManager)
                                    if (icon != null) {
                                        Image(
                                            bitmap = icon.toBitmap().asImageBitmap(),
                                            contentDescription = label,
                                            modifier = Modifier
                                                .size(24.dp)
                                                .clip(CircleShape)
                                                .clickable {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(links[index])).apply {
                                                        setPackage(app.activityInfo.packageName)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                        )
                                    } else {
                                        Text(
                                            text = label,
                                            color = Color(0xFFFF6B35),
                                            fontSize = 12.sp,
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(4.dp))
                                                .clickable {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(links[index])).apply {
                                                        setPackage(app.activityInfo.packageName)
                                                    }
                                                    context.startActivity(intent)
                                                }
                                                .padding(4.dp)
                                        )
                                    }
                                }
                            }
                            // Всегда показываем кнопку "Открыть в браузере"
                            Icon(
                                imageVector = Icons.Rounded.OpenInBrowser,
                                contentDescription = "Открыть в браузере",
                                tint = Color(0xFFFF6B35),
                                modifier = Modifier
                                    .size(24.dp)
                                    .clickable {
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(links[index]))
                                        context.startActivity(intent)
                                    }
                            )
                        }
                    }
                }
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

            // Highlight links in content
            val annotatedString = buildAnnotatedString {
                val matcher = Pattern.compile(
                    "(https?://[\\w\\-\\.]+(:\\d+)?(/[\\w\\-\\.]*)*(\\?[\\w\\-\\.=&%]*)?(#[\\w\\-]*)?)"
                ).matcher(post.content)
                var lastEnd = 0
                while (matcher.find()) {
                    append(post.content.substring(lastEnd, matcher.start()))
                    val url = matcher.group()
                    withStyle(
                        style = SpanStyle(
                            color = Color(0xFFFF6B35),
                            textDecoration = TextDecoration.Underline
                        )
                    ) {
                        append(url)
                    }
                    lastEnd = matcher.end()
                }
                append(post.content.substring(lastEnd))
            }
            Text(
                text = annotatedString,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) {
                    links.forEach { link ->
                        if (annotatedString.text.contains(link)) {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
                            context.startActivity(intent)
                        }
                    }
                }
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

            Spacer(modifier = Modifier.height(8.dp))

            // Add reaction buttons
            post.id?.let { postId ->
                ReactionButtons(
                    postId = postId,
                    userId = currentUid,
                    userReaction = post.userReaction,
                    likesCount = likesCount,
                    dislikesCount = dislikesCount,
                    viewModel = viewModel
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
                            model = comment.profile_photo?.takeIf { it.isNotBlank() }?.let { "https://api.skrinvex.su$it" },
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

    // ——— BottomSheet действий с комментарием ———
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
            // --- Аватар с переходом ---
            AsyncImage(
                model = comment.profile_photo?.takeIf { it.isNotBlank() }?.let { "https://api.skrinvex.su$it" },
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
                // --- Ник с переходом ---
                Text(
                    text = comment.nickname ?: comment.username ?: "Пользователь",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { onProfileClick() }
                )
                Text(
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