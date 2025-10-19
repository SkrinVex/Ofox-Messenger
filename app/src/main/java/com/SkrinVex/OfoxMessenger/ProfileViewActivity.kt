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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.disk.DiskCache
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.network.ProfileCheckResponse
import com.SkrinVex.OfoxMessenger.ui.common.MorphLoadingIndicator
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.ui.viewer.PhotoViewerActivity
import com.SkrinVex.OfoxMessenger.utils.CopyableText
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import androidx.compose.material3.ExperimentalMaterial3Api

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileViewScreen(
    profileState: ProfileViewState,
    viewModel: ProfileViewModel,
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

    BackHandler {
        // Если пользователь не найден, не позволяем закрыть экран через системную кнопку "назад"
        if (!profileState.userNotFound) {
            onBack()
        }
    }

    // Показываем/скрываем кнопку меню в тулбаре только если это не собственный профи��ь
    var showSheet by remember { mutableStateOf(false) }
    val showMenuButton = profileState.profileData != null && !profileState.isOwnProfile && !profileState.userNotFound

    Scaffold(
        topBar = {
            if (!profileState.userNotFound) {
                RoundedTopBar(title = "Профиль", onBack = onBack, showMenu = showMenuButton, onMenuClick = { showSheet = true })
            }
        },
        containerColor = Color(0xFF101010)
    ) { paddingValues ->
        when {
            profileState.isLoading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    MorphLoadingIndicator()                }
            }
            profileState.userNotFound -> {
                // Показываем диалог поверх всего содержимого
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.7f)),
                    contentAlignment = Alignment.Center
                ) {
                    UserNotFoundDialog(
                        onDismiss = onBack
                    )
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
                            onEditClick = onNavigateToProfileEdit,
                            isOwnProfile = profileState.isOwnProfile,
                            notificationId = notificationId,
                            isProcessing = profileState.isProcessingFriendRequest
                        )
                    }

                    // Показываем карточку "Статус" только если статус заполнен
                    if (!profile.status.isNullOrBlank()) {
                        item {
                            InfoCard(
                                title = "Статус",
                                icon = Icons.Rounded.Info,
                                value = profile.status
                            )
                        }
                    }

                    // Показываем карточку "О себе" только если bio заполнен
                    if (!profile.bio.isNullOrBlank()) {
                        item {
                            InfoCard(
                                title = "О себе",
                                icon = Icons.Rounded.Description,
                                value = profile.bio,
                                maxLines = 5
                            )
                        }
                    }

                    // Показываем личную информацию только если есть дата рождения
                    if (!profile.birthday.isNullOrBlank()) {
                        item {
                            InfoCard(
                                title = "Личная информация",
                                icon = Icons.Rounded.Cake,
                                value = "Дата рождения: ${profile.birthday}"
                            )
                        }
                    }

                    // Показываем email-карточку только если email есть
                    if (!profile.email.isNullOrBlank()) {
                        item {
                            EmailCard(email = profile.email, isOwnProfile = profileState.isOwnProfile)
                        }
                    }
                }

                // Bottom sheet для меню — открывается при нажатии на иконку в тулбаре (перенесено внутрь, где profile не null)
                if (showSheet) {
                    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
                    val sheetContext = LocalContext.current
                    ModalBottomSheet(
                        onDismissRequest = { showSheet = false },
                        sheetState = sheetState,
                        containerColor = Color(0xFF1E1E1E),
                        scrimColor = Color.Black.copy(alpha = 0.4f),
                        tonalElevation = 8.dp,
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
                    ) {
                        var confirmRemove by remember { mutableStateOf(false) }
                        val context = LocalContext.current

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 16.dp)
                        ) {
                            // Заголовок с именем пользователя
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = if (!profile.nickname.isNullOrBlank()) profile.nickname else profile.username ?: "Профиль",
                                        color = Color.White,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text("Меню", color = Color.Gray, fontSize = 13.sp)
                                }
                            }

                            Divider(color = Color.White.copy(alpha = 0.06f))
                            Spacer(modifier = Modifier.height(12.dp))

                            if (profile.friendship_status == "friends") {
                                OptionButton(
                                    icon = Icons.Rounded.Delete,
                                    label = "Удалить из друзей",
                                    onClick = { confirmRemove = true }
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                            Button(
                                onClick = { showSheet = false },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2A2A2A))
                            ) {
                                Text("Закрыть", color = Color.White)
                            }
                        }

                        if (confirmRemove) {
                            DeleteConfirmationDialog(
                                itemType = "друга",
                                onConfirm = {
                                    profile.user_id?.let { targetUid ->
                                        viewModel.removeFriend(targetUid) { success, message ->
                                            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                    confirmRemove = false
                                    showSheet = false
                                },
                                onDismiss = { confirmRemove = false }
                            )
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
fun RoundedTopBar(title: String, onBack: () -> Unit, showMenu: Boolean = false, onMenuClick: () -> Unit = {}) {
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
            Spacer(modifier = Modifier.weight(1f))
            if (showMenu) {
                IconButton(onClick = onMenuClick) {
                    Icon(
                        imageVector = Icons.Rounded.Menu,
                        contentDescription = "Меню",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun ProfileHeader(
    profile: ProfileCheckResponse,
    viewModel: ProfileViewModel,
    onEditClick: () -> Unit,
    notificationId: String?,
    isOwnProfile: Boolean,
    isProcessing: Boolean
) {
    val context = LocalContext.current
    val imageLoader = coil.Coil.imageLoader(context) // <-- используем глобальный кешированный ImageLoader

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column {
            // --- Фон профиля ---
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
                    .clickable(enabled = !profile.background_photo.isNullOrBlank()) {
                        profile.background_photo?.let { url ->
                            PhotoViewerActivity.start(context, url)
                        }
                    }
            ) {
                profile.background_photo?.let { url ->
                    var isBackgroundLoading by remember { mutableStateOf(true) }

                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(url)
                            .diskCacheKey(url)
                            .memoryCacheKey(url)
                            .crossfade(true)
                            .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                            .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
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
                            MorphLoadingIndicator()
                        }
                    }
                }

                // Градиентная тень для читаемости текста
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

            // --- Фото профиля ---
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(modifier = Modifier.size(72.dp)) {
                        var isProfilePhotoLoading by remember { mutableStateOf(true) }

                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(profile.profile_photo)
                                .diskCacheKey(profile.profile_photo)
                                .memoryCacheKey(profile.profile_photo)
                                .crossfade(true)
                                .diskCachePolicy(coil.request.CachePolicy.ENABLED)
                                .memoryCachePolicy(coil.request.CachePolicy.ENABLED)
                                .build(),
                            contentDescription = "Фото профиля",
                            imageLoader = imageLoader,
                            modifier = Modifier
                                .fillMaxSize()
                                .clip(CircleShape)
                                .background(Color(0xFF333333))
                                .clickable(enabled = !profile.profile_photo.isNullOrBlank()) {
                                    profile.profile_photo?.let { url -> PhotoViewerActivity.start(context, url) }
                                },
                            contentScale = ContentScale.Crop,
                            onState = { state ->
                                isProfilePhotoLoading = state is AsyncImagePainter.State.Loading
                            }
                        )

                        if (isProfilePhotoLoading) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MorphLoadingIndicator()
                            }
                        }

                        // Индикатор онлайн-статуса
                        if (profile.isOnline && !isOwnProfile) {
                            Box(
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .offset(2.dp, 2.dp)
                                    .size(16.dp)
                                    .background(Color(0xFF101010), CircleShape)
                                    .padding(2.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color(0xFF00C851), CircleShape)
                                )
                            }
                        }
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        val hasNickname = !profile.nickname.isNullOrBlank()
                        Text(
                            text = if (hasNickname) profile.nickname!! else "Никнейм не указан",
                            color = if (hasNickname) Color.White else Color(0xFFFF6B35),
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                        CopyableText(
                            text = AnnotatedString(profile.username ?: "username"),
                            modifier = Modifier.padding(8.dp),
                            style = TextStyle(color = Color.Gray, fontSize = 14.sp)
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
                                        viewModel.acceptFriendRequest(profile.user_id, notificationId) { success, message ->
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
                                        viewModel.declineFriendRequest(profile.user_id, notificationId) { success, message ->
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
                                // Кнопка удаления друга перемещена в меню (Bottom Sheet)
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
            MorphLoadingIndicator()
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
                SmartLinkText(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(8.dp))
            SmartLinkText(value, color = Color.White.copy(alpha = 0.9f), fontSize = 15.sp, maxLines = maxLines)
        }
    }
}

@Composable
fun EmailCard(email: String, isOwnProfile: Boolean) {
    var masked by remember { mutableStateOf(true) } // По умолчанию скрываем email
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

@Composable
fun UserNotFoundDialog(
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { }, // Пустая функция - диалог нельзя закрыть кликом вне области
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.ErrorOutline,
                    contentDescription = null,
                    tint = Color(0xFFFF6B35),
                    modifier = Modifier.size(28.dp)
                )
                Text(
                    text = "Пользователь не найден",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Этот пользователь был удален или его не существует.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )
                Text(
                    text = "Возможно, его аккаунт был деактивирован или удален администратором.",
                    color = Color.Gray,
                    fontSize = 12.sp,
                    lineHeight = 16.sp
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B35)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Вернуться назад",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        },
        dismissButton = null, // Убираем кнопку отмены
        containerColor = Color(0xFF1E1E1E).copy(alpha = 0.98f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.padding(16.dp)
    )
}