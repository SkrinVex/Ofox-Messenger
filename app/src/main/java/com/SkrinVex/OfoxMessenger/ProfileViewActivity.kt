package com.SkrinVex.OfoxMessenger

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.disk.DiskCache
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.ui.viewer.PhotoViewerActivity
import com.google.accompanist.systemuicontroller.rememberSystemUiController

class ProfileViewActivity : ComponentActivity() {
    companion object {
        const val REQUEST_CODE_EDIT_PROFILE = 1001
        const val RESULT_PROFILE_UPDATED = 1002
    }

    private lateinit var viewModel: ProfileViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()
        val uid = intent.getStringExtra("uid") ?: return finish()
        val friendUid = intent.getStringExtra("friend_uid")?.takeIf { it.isNotBlank() }
        val notificationId = intent.getStringExtra("notificationId") // Извлекаем notificationId

        viewModel = viewModels<ProfileViewModel> {
            ProfileViewModelFactory(uid, friendUid)
        }.value

        setContent {
            OfoxMessengerTheme {
                val state by viewModel.state.collectAsState()
                ProfileViewScreen(
                    profileState = state,
                    viewModel = viewModel,
                    uid = uid,
                    notificationId = notificationId, // Передаем notificationId
                    onNavigateToProfileEdit = {
                        if (state.isOwnProfile) {
                            val intent = Intent(this, ProfileEditActivity::class.java)
                            intent.putExtra("uid", uid)
                            startActivityForResult(intent, REQUEST_CODE_EDIT_PROFILE)
                        }
                    },
                    onBack = { finish() }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadProfileData()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_EDIT_PROFILE && resultCode == RESULT_PROFILE_UPDATED) {
            val updatedProfile = data?.getSerializableExtra("updated_profile") as? ProfileCheckResponse
            if (updatedProfile != null) {
                viewModel.updateProfile(updatedProfile)
            }
        }
    }
}

@Composable
fun ProfileViewScreen(
    profileState: ProfileViewState,
    viewModel: ProfileViewModel,
    uid: String,
    notificationId: String?,
    onNavigateToProfileEdit: () -> Unit,
    onBack: () -> Unit
) {
    val systemUiController = rememberSystemUiController()
    SideEffect {
        systemUiController.setSystemBarsColor(
            color = Color(0xFF101010),
            darkIcons = false
        )
    }

    BackHandler { onBack() }

    Scaffold(
        topBar = { RoundedTopBar(title = "Профиль", onBack = onBack) },
        containerColor = Color(0xFF101010)
    ) { paddingValues ->
        when {
            profileState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFFFF6B35))
                }
            }
            profileState.profileData != null -> {
                val profile = profileState.profileData
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        ProfileHeader(
                            profile = profile,
                            viewModel = viewModel,
                            uid = uid,
                            onEditClick = onNavigateToProfileEdit,
                            isOwnProfile = profileState.isOwnProfile,
                            notificationId = notificationId,
                            isProcessing = profileState.isProcessingFriendRequest
                        )
                    }
                    item {
                        InfoCard(
                            title = "Статус",
                            icon = Icons.Rounded.Info,
                            value = profile.status ?: "Статус не указан"
                        )
                    }
                    item {
                        InfoCard(
                            title = "О себе",
                            icon = Icons.Rounded.Description,
                            value = profile.bio ?: "Описание отсутствует",
                            maxLines = 5
                        )
                    }
                    item {
                        InfoCard(
                            title = "Личная информация",
                            icon = Icons.Rounded.Cake,
                            value = "Дата рождения: ${profile.birthday ?: "Не указана"}"
                        )
                    }
                    if (profile.email != null) {
                        item {
                            EmailCard(email = profile.email!!, isOwnProfile = profileState.isOwnProfile)
                        }
                    }
                }
            }
            else -> {
                Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Не удалось загрузить профиль: ${profileState.error ?: "Неизвестная ошибка"}",
                        color = Color.White,
                        fontSize = 16.sp
                    )
                }
            }
        }
    }
}

@Composable
fun RoundedTopBar(title: String, onBack: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                start = 12.dp,
                end = 12.dp,
                bottom = 8.dp
            )
            .height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun ProfileHeader(
    profile: ProfileCheckResponse,
    viewModel: ProfileViewModel,
    uid: String,
    onEditClick: () -> Unit,
    notificationId: String?,
    isOwnProfile: Boolean,
    isProcessing: Boolean
) {
    val context = LocalContext.current
    val imageLoader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("image_cache"))
                .maxSizePercent(0.02)
                .build()
        }
        .allowHardware(false)
        .build()

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clickable(enabled = !profile.background_photo.isNullOrBlank()) {
                        profile.background_photo?.let { path ->
                            val fullUrl = "https://api.skrinvex.su$path"
                            PhotoViewerActivity.start(context, fullUrl)
                        }
                    }
            ) {
                profile.background_photo?.let { url ->
                    var isBackgroundLoading by remember { mutableStateOf(true) }
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(if (url.isNotBlank()) "https://api.skrinvex.su$url" else null)
                            .fallback(android.R.drawable.ic_menu_gallery)
                            .error(android.R.drawable.ic_menu_gallery)
                            .build(),
                        contentDescription = "Фон профиля",
                        imageLoader = imageLoader,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        onState = { state ->
                            isBackgroundLoading = state is AsyncImagePainter.State.Loading
                        }
                    )
                    if (isBackgroundLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.3f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = Color(0xFFFF6B35),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                            )
                        )
                )
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF333333))
                            .clickable(enabled = !profile.profile_photo.isNullOrBlank()) {
                                profile.profile_photo?.let { path ->
                                    val fullUrl = "https://api.skrinvex.su$path"
                                    PhotoViewerActivity.start(context, fullUrl)
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        var isProfilePhotoLoading by remember { mutableStateOf(true) }
                        AsyncImage(
                            model = ImageRequest.Builder(LocalContext.current)
                                .data(if (profile.profile_photo?.isNotBlank() == true) "https://api.skrinvex.su${profile.profile_photo}" else null)
                                .fallback(android.R.drawable.ic_menu_gallery)
                                .error(android.R.drawable.ic_menu_gallery)
                                .build(),
                            contentDescription = "Фото профиля",
                            imageLoader = imageLoader,
                            modifier = Modifier
                                .size(72.dp)
                                .clip(CircleShape),
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                isProfilePhotoLoading = state is AsyncImagePainter.State.Loading
                            }
                        )
                        if (isProfilePhotoLoading) {
                            CircularProgressIndicator(
                                color = Color(0xFFFF6B35),
                                modifier = Modifier.size(24.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = profile.nickname ?: "Никнейм не указан",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${profile.username ?: "username"}",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = (profile.profile_completion) / 100f,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape),
                            color = Color(0xFFFF6B35),
                            trackColor = Color(0xFF333333)
                        )
                    }

                    if (isOwnProfile) {
                        IconButton(onClick = onEditClick) {
                            Icon(
                                imageVector = Icons.Rounded.Edit,
                                contentDescription = "Редактировать",
                                tint = Color(0xFFFF6B35)
                            )
                        }
                    }
                }

                // Кнопки для взаимодействия с друзьями
                if (!isOwnProfile && profile.user_id != null) {
                    Spacer(modifier = Modifier.height(12.dp))
                    when (profile.friendship_status ?: "none") {
                        "none" -> {
                            FriendActionButton(
                                text = "Добавить в друзья",
                                icon = Icons.Rounded.PersonAdd,
                                backgroundColor = Color(0xFFFF6B35),
                                textColor = Color.Black,
                                isLoading = isProcessing,
                                onClick = {
                                    viewModel.sendFriendRequest(profile.user_id) { success, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        "request_sent" -> {
                            FriendActionButton(
                                text = "Отменить заявку",
                                icon = Icons.Rounded.Cancel,
                                backgroundColor = Color(0xFF666666),
                                textColor = Color.White,
                                isLoading = isProcessing,
                                onClick = {
                                    viewModel.cancelFriendRequest(profile.user_id) { success, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                        "request_received" -> {
                            Text(
                                text = "Пользователь отправил вам запрос в друзья",
                                color = Color.White,
                                fontSize = 14.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FriendActionButton(
                                    text = "Принять запрос",
                                    icon = Icons.Rounded.Check,
                                    backgroundColor = Color(0xFF4CAF50),
                                    textColor = Color.White,
                                    isLoading = isProcessing,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.acceptFriendRequest(profile.user_id, notificationId) { success, message -> // Исправлено: добавлен notificationId
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                                FriendActionButton(
                                    text = "Отклонить",
                                    icon = Icons.Rounded.Close,
                                    backgroundColor = Color(0xFFF44336),
                                    textColor = Color.White,
                                    isLoading = isProcessing,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.declineFriendRequest(profile.user_id, notificationId) { success, message -> // Исправлено: добавлен notificationId
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        "friends" -> {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FriendActionButton(
                                    text = "Начать чат",
                                    icon = Icons.Rounded.Message,
                                    backgroundColor = Color(0xFFFF6B35),
                                    textColor = Color.Black,
                                    isLoading = false,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        val intent = Intent(context, ChatActivity::class.java).apply {
                                            putExtra("friend_uid", profile.user_id)
                                            putExtra("friend_name", profile.nickname ?: profile.username ?: "Пользователь")
                                            putExtra("friend_photo", profile.profile_photo)
                                        }
                                        context.startActivity(intent)
                                    }
                                )
                                FriendActionButton(
                                    text = "Удалить друга",
                                    icon = Icons.Rounded.Delete,
                                    backgroundColor = Color(0xFFF44336),
                                    textColor = Color.White,
                                    isLoading = isProcessing,
                                    modifier = Modifier.weight(1f),
                                    onClick = {
                                        viewModel.removeFriend(profile.user_id) { success, message ->
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                )
                            }
                        }
                        else -> {
                            FriendActionButton(
                                text = "Добавить в друзья",
                                icon = Icons.Rounded.PersonAdd,
                                backgroundColor = Color(0xFFFF6B35),
                                textColor = Color.Black,
                                isLoading = isProcessing,
                                onClick = {
                                    viewModel.sendFriendRequest(profile.user_id) { success, message ->
                                        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FriendActionButton(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    backgroundColor: Color,
    textColor: Color,
    isLoading: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = if (isLoading) { {} } else onClick,
        colors = ButtonDefaults.buttonColors(containerColor = backgroundColor),
        modifier = modifier.fillMaxWidth(),
        enabled = !isLoading
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = textColor,
                strokeWidth = 2.dp
            )
            Spacer(modifier = Modifier.width(8.dp))
        } else {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        Text(
            text = text,
            color = textColor,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

@Composable
fun InfoCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, maxLines: Int = 2) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = title, tint = Color(0xFFFF6B35), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            Text(value, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = maxLines)
        }
    }
}

@Composable
fun EmailCard(email: String, isOwnProfile: Boolean) {
    var masked by remember { mutableStateOf(!isOwnProfile) }
    val displayedEmail = if (masked && email.contains("@")) {
        val parts = email.split("@")
        if (parts[0].length > 2) parts[0][0] + "*****@" + parts[1] else "*****@" + parts[1]
    } else email

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Email, contentDescription = "Email", tint = Color(0xFFFF6B35), modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Email", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                if (isOwnProfile) {
                    IconButton(onClick = { masked = !masked }) {
                        Icon(
                            imageVector = if (masked) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff,
                            contentDescription = "Показать/скрыть",
                            tint = Color(0xFFFF6B35)
                        )
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(displayedEmail, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp)
        }
    }
}