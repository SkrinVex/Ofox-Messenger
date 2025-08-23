package com.SkrinVex.OfoxMessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.animateColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import com.SkrinVex.OfoxMessenger.network.MainPageResponse
import com.SkrinVex.OfoxMessenger.network.NotificationItem
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.network.NewsItem
import com.SkrinVex.OfoxMessenger.ui.dialogs.DialogController
import com.SkrinVex.OfoxMessenger.ui.dialogs.GlobalDialogHost
import com.SkrinVex.OfoxMessenger.ui.viewer.PhotoViewerActivity
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText

class MainPageActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()
        val uid = intent.getStringExtra("uid") ?: ""
        val viewModel: MainPageViewModel by viewModels {
            MainPageViewModelFactory(uid)
        }

        setContent {
            OfoxMessengerTheme {
                val state by viewModel.state.collectAsState()
                GlobalDialogHost()
                MainScreen(
                    mainPageState = state,
                    viewModel = viewModel,
                    onNavigateToProfileView = {
                        val intent = Intent(this, ProfileViewActivity::class.java)
                        intent.putExtra("uid", uid)
                        startActivityForResult(intent, ProfileEditActivity.RESULT_PROFILE_UPDATED)
                    }
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == ProfileEditActivity.RESULT_PROFILE_UPDATED && resultCode == ProfileEditActivity.RESULT_PROFILE_UPDATED) {
            val updatedProfile = data?.getSerializableExtra("updated_profile") as? ProfileCheckResponse
            if (updatedProfile != null) {
                val viewModel: MainPageViewModel by viewModels {
                    MainPageViewModelFactory(intent.getStringExtra("uid") ?: "")
                }
                viewModel.updateMainPageData(updatedProfile)
            }
        }
    }
}

@Composable
fun MainScreen(
    mainPageState: MainPageState,
    viewModel: MainPageViewModel,
    onNavigateToProfileView: () -> Unit
) {
    val context = LocalContext.current
    val uid = (context as? MainPageActivity)?.intent?.getStringExtra("uid") ?: ""

    BackHandler {
        (context as? ComponentActivity)?.finish()
    }

    var selectedTab by remember { mutableStateOf("news") } // "news" или "notifications"

    Scaffold(
        containerColor = Color(0xFF101010),
        floatingActionButton = {
            when (selectedTab) {
                "notifications" -> {
                    if (mainPageState.mainPageData?.notifications?.isNotEmpty() == true) {
                        FloatingActionButton(
                            onClick = {
                                viewModel.clearAllNotifications { success, message ->
                                    Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                }
                            },
                            containerColor = Color(0xFFFF6B35),
                            contentColor = Color.Black,
                            shape = CircleShape,
                            modifier = Modifier.size(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Delete,
                                contentDescription = "Очистить уведомления",
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
                "news" -> {
                    FloatingActionButton(
                        onClick = {
                            val intent = Intent(context, PostsActivity::class.java)
                            intent.putExtra("uid", uid)
                            context.startActivity(intent)
                        },
                        containerColor = Color(0xFFFF6B35),
                        contentColor = Color.Black,
                        shape = CircleShape,
                        modifier = Modifier.size(56.dp)
                    ) {
                        Text(
                            text = "Посты",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    ) { paddingValues ->
        if (mainPageState.isLoading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color(0xFFFF6B35))
            }
        } else if (mainPageState.mainPageData?.profile != null) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    UserHeaderCard(profile = mainPageState.mainPageData.profile, onClick = onNavigateToProfileView)
                }

                item {
                    val actions = listOf(
                        ActionItem("Профиль", Icons.Rounded.Person) { onNavigateToProfileView() },
                        ActionItem("Сообщения", Icons.Rounded.Chat) { DialogController.showComingSoonDialog() },
                        ActionItem("Друзья", Icons.Rounded.People) {
                            val intent = Intent(context, FriendsActivity::class.java)
                            intent.putExtra("uid", uid)
                            context.startActivity(intent)
                        },
                        ActionItem("Настройки", Icons.Rounded.Settings) { DialogController.showComingSoonDialog() }
                    )
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.height(220.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        items(actions) { action ->
                            ActionCard(action = action)
                        }
                    }
                }

                item {
                    ModernToggle(
                        selectedTab = selectedTab,
                        onTabSelected = { tab -> selectedTab = tab },
                        hasNotifications = mainPageState.mainPageData?.notifications?.isNotEmpty() == true,
                        tab1Text = "Новости",
                        tab1Value = "news",
                        tab2Text = "Уведомления",
                        tab2Value = "notifications"
                    )
                }

                if (selectedTab == "news") {
                    if (!mainPageState.mainPageData.news_feed.isNullOrEmpty()) {
                        items(mainPageState.mainPageData.news_feed) { newsItem ->
                            NewsCard(newsItem = newsItem)
                        }
                    } else {
                        item {
                            Text(
                                text = "Нет новостей",
                                color = Color.White,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                } else {
                    val notifications = mainPageState.mainPageData.notifications ?: emptyList()
                    if (notifications.isNotEmpty()) {
                        items(notifications) { notification ->
                            NotificationCard(notification = notification)
                        }
                    } else {
                        item {
                            Text(
                                text = "Нет уведомлений",
                                color = Color.White,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                    }
                }
            }
        } else {
            Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                Text(text = "Не удалось загрузить данные: ${mainPageState.error}", color = Color.White)
            }
        }
    }
}

@Composable
fun UserHeaderCard(profile: ProfileCheckResponse, onClick: () -> Unit) {
    val context = LocalContext.current

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        onClick = onClick
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
                    .clickable(enabled = !profile.profile_photo.isNullOrBlank()) {
                        profile.profile_photo?.let { url ->
                            PhotoViewerActivity.start(context, url)
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = profile.profile_photo?.takeIf { it.isNotBlank() },
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    placeholder = painterResource(id = R.drawable.logo),
                    error = painterResource(id = R.drawable.logo)
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Привет, ${profile.nickname ?: profile.username ?: "Пользователь"}!",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Профиль заполнен на ${profile.profile_completion}%",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = (profile.profile_completion ?: 0) / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = Color(0xFFFF6B35),
                    trackColor = Color(0xFF333333)
                )
            }
        }
    }
}

data class ActionItem(val title: String, val icon: ImageVector, val onClick: () -> Unit)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionCard(action: ActionItem) {
    Card(
        onClick = action.onClick,
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = action.icon,
                contentDescription = action.title,
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = action.title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun NewsCard(newsItem: NewsItem) {
    val context = LocalContext.current
    var isImageLoading by remember { mutableStateOf(true) }

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
            Text(
                text = newsItem.title,
                style = MaterialTheme.typography.titleLarge.copy(color = Color(0xFFFF6B35))
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Дата: ${newsItem.date}",
                style = MaterialTheme.typography.bodySmall.copy(color = Color.Gray)
            )
            Spacer(modifier = Modifier.height(8.dp))
            SmartLinkText(
                text = newsItem.content,
                style = MaterialTheme.typography.bodyMedium
            )
            newsItem.image_url?.takeIf { it.isNotBlank() }?.let { url ->
                Spacer(modifier = Modifier.height(10.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .clickable {
                            PhotoViewerActivity.start(context, url)
                        },
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = url,
                        contentDescription = "Новостное изображение",
                        contentScale = ContentScale.Crop,
                        onLoading = { isImageLoading = true },
                        onSuccess = { isImageLoading = false },
                        onError = { isImageLoading = false },
                        modifier = Modifier.matchParentSize()
                    )

                    if (isImageLoading) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .background(
                                    Color.Black.copy(alpha = 0.3f),
                                    RoundedCornerShape(8.dp)
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(48.dp),
                                color = Color(0xFFFF6B35),
                                strokeWidth = 4.dp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NotificationCard(notification: NotificationItem) {
    val context = LocalContext.current
    val viewModel: MainPageViewModel = viewModel(
        factory = MainPageViewModelFactory(
            (context as? MainPageActivity)?.intent?.getStringExtra("uid") ?: ""
        )
    )
    val profileViewModel: ProfileViewModel = viewModel(
        factory = ProfileViewModelFactory(
            uid = (context as? MainPageActivity)?.intent?.getStringExtra("uid") ?: "",
            friendUid = notification.from_uid
        )
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                notification.from_uid?.let { fromUid ->
                    val intent = Intent(context, ProfileViewActivity::class.java)
                    intent.putExtra("uid", (context as? MainPageActivity)?.intent?.getStringExtra("uid"))
                    intent.putExtra("friend_uid", fromUid)
                    intent.putExtra("notificationId", notification.id)
                    context.startActivity(intent)
                }
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = notification.title,
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = notification.created_at,
                color = Color.Gray,
                fontSize = 12.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            SmartLinkText(
                text = notification.content,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 14.sp
            )
            if (notification.title == "Новая заявка в друзья" && notification.from_uid != null && notification.id != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Button(
                        onClick = {
                            profileViewModel.acceptFriendRequest(notification.from_uid, notification.id) { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35))
                    ) {
                        Text("Принять")
                    }
                    Button(
                        onClick = {
                            profileViewModel.declineFriendRequest(notification.from_uid, notification.id) { success, message ->
                                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF333333))
                    ) {
                        Text("Отклонить")
                    }
                }
            }
        }
    }
}