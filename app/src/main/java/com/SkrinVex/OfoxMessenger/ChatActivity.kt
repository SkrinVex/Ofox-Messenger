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
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
class ChatActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Настройка для правильного поведения с клавиатурой
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color(0xFF0A0A0A).toArgb()
        window.navigationBarColor = Color(0xFF0A0A0A).toArgb()

        val friendUid = intent.getStringExtra("friend_uid") ?: return finish()
        val friendName = intent.getStringExtra("friend_name") ?: "Пользователь"
        val friendPhoto = intent.getStringExtra("friend_photo")

        val viewModel: ChatViewModel by viewModels {
            ChatViewModelFactory(FirebaseAuth.getInstance().currentUser?.uid ?: "", friendUid)
        }

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
            color = Color(0xFF0A0A0A),
            darkIcons = false
        )
    }

    BackHandler { onBack() }

    val state by viewModel.state.collectAsState()
    val lazyListState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var selectedMessage by remember { mutableStateOf<Message?>(null) }
    var showDeleteDialog by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages) {
        if (state.messages.isNotEmpty()) {
            scope.launch {
                lazyListState.animateScrollToItem(state.messages.size - 1)
            }
        }
    }

    Scaffold(
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
            ),
        topBar = {
            ChatTopBar(
                friendName = friendName,
                friendPhoto = friendPhoto,
                onBack = onBack
            )
        },
        bottomBar = {
            MessageInputField(
                messageText = state.messageText,
                onMessageChange = viewModel::updateMessageText,
                onSendClick = { viewModel.sendMessage() },
                isSending = state.isSending,
                modifier = Modifier.fillMaxWidth()
            )
        },
        containerColor = Color.Transparent
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
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
                items(state.messages) { message ->
                    MessageCard(
                        message = message,
                        isOwnMessage = message.senderId == FirebaseAuth.getInstance().currentUser?.uid,
                        onLongClick = { selectedMessage = message }
                    )
                }
            }
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
fun ChatTopBar(
    friendName: String,
    friendPhoto: String?,
    onBack: () -> Unit
) {
    val statusBarHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp + statusBarHeight)
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
                .padding(top = statusBarHeight)
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
                        .data(if (friendPhoto?.isNotBlank() == true)
                            "https://api.skrinvex.su$friendPhoto" else null)
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

                Text(
                    text = friendName,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }
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
    val backgroundColor = if (isOwnMessage) {
        listOf(Color(0xFFFF6B35), Color(0xFFFF8A5B))
    } else {
        listOf(Color(0xFF2A2A2A), Color(0xFF3A3A3A))
    }
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
                    .background(
                        brush = Brush.linearGradient(
                            colors = backgroundColor
                        )
                    )
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