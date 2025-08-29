package com.SkrinVex.OfoxMessenger

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()

        // Настройка для правильного поведения с клавиатурой
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color(0xFF0A0A0A).toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

        val friendUid = intent.getStringExtra("friend_uid") ?: return finish()
        val friendName = intent.getStringExtra("friend_name") ?: "Пользователь"
        val friendPhoto = intent.getStringExtra("friend_photo")

        val viewModel: ChatViewModel by viewModels {
            ChatViewModelFactory(FirebaseAuth.getInstance().currentUser?.uid ?: "", friendUid)
        }

        viewModel.startFriendStatusListener()

        setContent {
            OfoxMessengerTheme {
                ChatScreen(
                    viewModel = viewModel,
                    friendName = friendName,
                    friendPhoto = friendPhoto,
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val friendUid = intent.getStringExtra("friend_uid") ?: return

        // Помечаем что мы в чате именно с этим пользователем
        FirebaseDatabase.getInstance()
            .getReference("users/$uid/in_chat_with")
            .setValue(friendUid)
    }

    override fun onPause() {
        super.onPause()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return

        val ref = FirebaseDatabase.getInstance().getReference("users/$uid")
        ref.child("in_chat_with").setValue(null)
        ref.child("typing").setValue(false) // сброс "печатает"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    friendName: String,
    friendPhoto: String?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val systemUiController = rememberSystemUiController()

    SideEffect {
        systemUiController.setSystemBarsColor(
            color = Color.Transparent,
            darkIcons = false
        )
        systemUiController.setNavigationBarColor(
            color = Color.Transparent,
            darkIcons = false,
            navigationBarContrastEnforced = false
        )
    }

    BackHandler { onBack() }

    val state by viewModel.state.collectAsState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    val friendStatus by viewModel.friendStatus.collectAsState()

    // Сохраняем позицию скролла при загрузке новых сообщений
    var savedScrollPosition by remember { mutableStateOf<Int?>(null) }
    var savedScrollOffset by remember { mutableStateOf(0) }

    // Track if user is at the bottom (within 3 messages from the end)
    val isAtBottom by remember {
        derivedStateOf {
            val layoutInfo = lazyListState.layoutInfo
            val totalItems = layoutInfo.totalItemsCount
            if (totalItems == 0) true
            else {
                val lastVisibleItemIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
                lastVisibleItemIndex >= totalItems - 3
            }
        }
    }

    // Проверяем загрузку дополнительных сообщений
    LaunchedEffect(lazyListState.firstVisibleItemIndex, lazyListState.firstVisibleItemScrollOffset) {
        if (lazyListState.firstVisibleItemIndex == 0 &&
            lazyListState.firstVisibleItemScrollOffset == 0 &&
            state.canLoadMore &&
            !state.isLoadingMore
        ) {
            // Сохраняем текущую позицию перед загрузкой
            val currentFirstVisible = lazyListState.firstVisibleItemIndex
            val currentOffset = lazyListState.firstVisibleItemScrollOffset

            savedScrollPosition = currentFirstVisible
            savedScrollOffset = currentOffset

            viewModel.loadMoreMessages()
        }
    }

    // Восстанавливаем позицию скролла после загрузки новых сообщений
    LaunchedEffect(state.messages.size, state.isLoadingMore) {
        if (!state.isLoadingMore && savedScrollPosition != null) {
            // Вычисляем новую позицию с учетом добавленных сообщений
            val previousMessageCount = state.messages.size - (savedScrollPosition!! + 1)
            val newPosition = state.messages.size - previousMessageCount - 1

            if (newPosition >= 0) {
                lazyListState.scrollToItem(newPosition, savedScrollOffset)
            }

            savedScrollPosition = null
            savedScrollOffset = 0
        }
    }

    // Автоскролл только для собственных новых сообщений
    LaunchedEffect(state.messages.size) {
        if (state.messages.isNotEmpty() && !state.isLoadingMore) {
            val lastMessage = state.messages.last()
            // Скроллим вниз только если это наше сообщение ИЛИ мы уже внизу
            if (lastMessage.senderId == FirebaseAuth.getInstance().currentUser?.uid || isAtBottom) {
                scope.launch {
                    lazyListState.animateScrollToItem(state.messages.size - 1)
                }
            }
        }
    }

    // Группировка сообщений по датам
    val dateFormatter = remember { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()) }
    val displayDateFormatter = remember { SimpleDateFormat("dd MMMM yyyy", Locale.getDefault()) }
    val groupedMessages = remember(state.messages) {
        state.messages.groupBy { message ->
            dateFormatter.format(Date(message.timestamp))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0A0A0A),
                        Color(0xFF1A1A1A),
                        Color(0xFF0A0A0A)
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Фиксированный ToolBar
            ChatTopBar(
                friendName = friendName,
                friendPhoto = friendPhoto,
                status = friendStatus,
                onBack = onBack
            )

            // Контент чата
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    state.isLoading -> {
                        // Показываем крутилку только при первичной загрузке
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = Color(0xFFFF6B35))
                        }
                    }
                    state.messages.isEmpty() -> {
                        EmptyChatPlaceholder()
                    }
                    else -> {
                        Box(modifier = Modifier.fillMaxSize()) {
                            LazyColumn(
                                state = lazyListState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 12.dp,
                                    end = 12.dp,
                                    top = 8.dp,
                                    bottom = 16.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                groupedMessages.entries.sortedBy { it.key }.forEach { (dateKey, messages) ->
                                    stickyHeader {
                                        DateHeader(
                                            date = displayDateFormatter.format(dateFormatter.parse(dateKey) ?: Date())
                                        )
                                    }
                                    items(messages) { message ->
                                        MessageCard(
                                            message = message,
                                            isOwnMessage = message.senderId == FirebaseAuth.getInstance().currentUser?.uid,
                                            onLongClick = { selectedMessage = message }
                                        )
                                    }
                                }
                            }

                            // Индикатор загрузки сверху
                            androidx.compose.animation.AnimatedVisibility(
                                visible = state.isLoadingMore,
                                enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f),
                                exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 16.dp)
                            ) {
                                Surface(
                                    modifier = Modifier
                                        .wrapContentSize()
                                        .shadow(4.dp, CircleShape),
                                    shape = CircleShape,
                                    color = Color(0xFF2A2A2A).copy(alpha = 0.9f)
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Color(0xFFFF6B35),
                                            strokeWidth = 2.dp
                                        )
                                        Text(
                                            text = "Загружаем сообщения...",
                                            color = Color.White,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Scroll to bottom button
                androidx.compose.animation.AnimatedVisibility(
                    visible = !isAtBottom,
                    enter = fadeIn(animationSpec = tween(200)) + scaleIn(initialScale = 0.8f, animationSpec = tween(200)),
                    exit = fadeOut(animationSpec = tween(200)) + scaleOut(targetScale = 0.8f, animationSpec = tween(200)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(bottom = 30.dp, end = 16.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            scope.launch {
                                if (state.messages.isNotEmpty()) {
                                    lazyListState.animateScrollToItem(state.messages.size - 1)
                                }
                            }
                        },
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.White,
                        shape = CircleShape,
                        modifier = Modifier
                            .size(48.dp)
                            .shadow(6.dp, CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowDownward,
                            contentDescription = "Scroll to bottom",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Поле ввода с отступами
            MessageInputField(
                messageText = state.messageText,
                onMessageChange = viewModel::updateMessageText,
                onSendClick = { viewModel.sendMessage() },
                isSending = state.isSending,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.ime)
                    .navigationBarsPadding()
            )
        }

        // Bottom sheet для действий с сообщениями
        if (selectedMessage != null) {
            ModalBottomSheet(
                onDismissRequest = { selectedMessage = null },
                containerColor = Color(0xFF1A1A1A),
                contentColor = Color.White,
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                tonalElevation = 12.dp,
                dragHandle = {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(4.dp)
                                .background(
                                    Color(0xFFFF6B35).copy(alpha = 0.8f),
                                    shape = RoundedCornerShape(2.dp)
                                )
                        )
                    }
                }
            ) {
                BottomSheetContent(
                    item = selectedMessage!!,
                    isOwnItem = selectedMessage!!.senderId == FirebaseAuth.getInstance().currentUser?.uid,
                    onCopy = {
                        copyToClipboard(context, selectedMessage!!.content)
                        selectedMessage = null
                    },
                    onDelete = {
                        showDeleteDialog = true
                    }
                )
            }
        }

        // Диалог подтверждения удаления
        if (showDeleteDialog && selectedMessage != null) {
            DeleteConfirmationDialog(
                itemType = "сообщение",
                onConfirm = {
                    viewModel.deleteMessage(selectedMessage!!.id)
                    showDeleteDialog = false
                    selectedMessage = null
                },
                onDismiss = {
                    showDeleteDialog = false
                    selectedMessage = null
                }
            )
        }
    }
}

@Composable
fun DateHeader(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .wrapContentSize()
                .shadow(4.dp, RoundedCornerShape(16.dp)),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xFF2A2A2A).copy(alpha = 0.8f)
        ) {
            Text(
                text = date,
                color = Color.White.copy(alpha = 0.7f),
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageCard(
    message: Message,
    isOwnMessage: Boolean,
    onLongClick: () -> Unit
) {
    val alignment = if (isOwnMessage) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (isOwnMessage) Color(0xFFFF6B35) else Color(0xFF2A2A2A)
    val textColor = if (isOwnMessage) Color.Black else Color.White
    val formatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        contentAlignment = alignment
    ) {
        Card(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = { },
                    onLongClick = onLongClick
                )
                .shadow(
                    elevation = 4.dp,
                    shape = RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                        bottomEnd = if (isOwnMessage) 4.dp else 16.dp
                    )
                ),
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isOwnMessage) 16.dp else 4.dp,
                bottomEnd = if (isOwnMessage) 4.dp else 16.dp
            ),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .background(backgroundColor)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    SmartLinkText(
                        text = message.content,
                        color = textColor,
                        fontSize = 15.sp,
                        lineHeight = 20.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isOwnMessage) {
                            Text(
                                text = when (message.status) {
                                    "sent" -> "•"
                                    "delivered" -> "••"
                                    "read" -> "✓✓"
                                    else -> ""
                                },
                                color = textColor.copy(alpha = 0.7f),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Text(
                            text = formatter.format(Date(message.timestamp)),
                            color = textColor.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyChatPlaceholder() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color(0xFFFF6B35).copy(alpha = 0.2f),
                                Color(0xFF1A1A1A).copy(alpha = 0.8f),
                                Color(0xFF0A0A0A).copy(alpha = 0.9f)
                            ),
                            radius = 400f
                        )
                    )
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Маскот с фото
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .background(
                                Color.Transparent
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(R.drawable.lisa_hello)
                                .fallback(android.R.drawable.ic_menu_gallery)
                                .error(android.R.drawable.ic_menu_gallery)
                                .build(),
                            contentDescription = "Маскот ЧАО-такт",
                            modifier = Modifier
                                .size(100.dp)
                                .clip(CircleShape)
                                .background(
                                    brush = Brush.radialGradient(
                                        colors = listOf(
                                            Color(0xFFFF6B35).copy(alpha = 0.2f),
                                            Color.Transparent
                                        ),
                                        radius = 100f
                                    )
                                ),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }

                    Text(
                        text = "Привет! 👋",
                        color = Color(0xFFFF6B35),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Text(
                        text = "В этом чате пока нет сообщений.\nНачните общение и отправьте первое сообщение!",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 22.sp
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🧡",
                            fontSize = 24.sp
                        )
                        Text(
                            text = "Приятного общения!",
                            color = Color(0xFFFF6B35),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "🧡",
                            fontSize = 24.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ChatTopBar(
    friendName: String,
    friendPhoto: String?,
    status: UserStatus, // <--- добавляем сюда
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            ),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF1A1A1A),
                            Color(0xFF2A2A2A),
                            Color(0xFFFF6B35).copy(alpha = 0.2f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Color(0xFFFF6B35).copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(friendPhoto?.takeIf { it.isNotBlank() })
                        .fallback(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .build(),
                    contentDescription = "Фото друга",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF6B35).copy(alpha = 0.3f),
                                    Color(0xFF333333)
                                )
                            )
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = friendName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    // Fixed status logic
                    AnimatedContent(
                        targetState = status,
                        label = "status_anim"
                    ) { st ->
                        when {
                            st.isTyping && st.inChatWith == FirebaseAuth.getInstance().currentUser?.uid -> TypingDots()
                            st.isOnline -> OnlineIndicator()
                            st.lastActive != null -> LastSeenText(st.lastActive)
                            else -> Text(
                                text = "Оффлайн",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun OnlineIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color.Green.copy(alpha = alpha))
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text("В сети", color = Color.Gray, fontSize = 13.sp)
    }
}

@Composable
fun TypingDots() {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val scales = List(3) { i ->
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, delayMillis = i * 150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot$i"
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Печатает", color = Color.Gray, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Row {
            scales.forEach { scale ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .scale(scale.value)
                        .clip(CircleShape)
                        .background(Color.Gray)
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    }
}

@Composable
fun LastSeenText(timestamp: Long) {
    Text(
        text = "Был(а) в сети: " + formatLastActive(timestamp),
        color = Color.Gray,
        fontSize = 13.sp
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageInputField(
    messageText: String,
    onMessageChange: (String) -> Unit,
    onSendClick: () -> Unit,
    isSending: Boolean,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .padding(12.dp)
            .shadow(6.dp, RoundedCornerShape(24.dp)),
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF2A2A2A),
                            Color(0xFF1A1A1A),
                            Color(0xFFFF6B35).copy(alpha = 0.1f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier
                        .weight(1f)
                        .background(Color.Transparent),
                    placeholder = {
                        Text(
                            "Введите сообщение...",
                            color = Color.Gray.copy(alpha = 0.8f)
                        )
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        cursorColor = Color(0xFFFF6B35)
                    ),
                    maxLines = 4
                )

                AnimatedVisibility(
                    visible = !isSending,
                    enter = scaleIn(animationSpec = tween(200)) + fadeIn(),
                    exit = scaleOut(animationSpec = tween(200)) + fadeOut()
                ) {
                    IconButton(
                        onClick = onSendClick,
                        enabled = messageText.isNotBlank(),
                        modifier = Modifier
                            .size(40.dp)
                            .background(
                                if (messageText.isNotBlank())
                                    Color(0xFFFF6B35)
                                else
                                    Color.Gray.copy(alpha = 0.3f),
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Send,
                            contentDescription = "Отправить",
                            tint = if (messageText.isNotBlank()) Color.White else Color.Gray,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                AnimatedVisibility(
                    visible = isSending,
                    enter = scaleIn(animationSpec = tween(200)) + fadeIn(),
                    exit = scaleOut(animationSpec = tween(200)) + fadeOut()
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFFFF6B35),
                        strokeWidth = 2.dp
                    )
                }
            }
        }
    }
}

private fun formatLastActive(timestamp: Long): String {
    val diff = System.currentTimeMillis() - timestamp
    return when {
        diff < 60_000 -> "только что"
        diff < 3_600_000 -> "${diff / 60_000} мин. назад"
        diff < 86_400_000 -> "${diff / 3_600_000} ч. назад"
        else -> SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(timestamp))
    }
}

// Функция для копирования в буфер обмена с уведомлением
private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clip = ClipData.newPlainText("message", text)
    clipboard.setPrimaryClip(clip)

    Toast.makeText(
        context,
        "Сообщение скопировано",
        Toast.LENGTH_SHORT
    ).show()
}