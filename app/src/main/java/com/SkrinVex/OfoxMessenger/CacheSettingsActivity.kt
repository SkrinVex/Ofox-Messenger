package com.SkrinVex.OfoxMessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class CacheSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OfoxMessengerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF0A0A0A)
                ) {
                    CacheSettingsScreen(
                        onBack = { finish() },
                        onSave = {
                            CacheManager.saveSettings(this)
                            (application as App).initImageLoader()
                        },
                        onClearCache = {
                            (application as App).clearImageCache()
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CacheSettingsScreen(
    onBack: () -> Unit = {},
    onSave: () -> Unit = {},
    onClearCache: () -> Unit = {}
) {
    val OfoxOrange = Color(0xFFFF6B35)
    val OfoxCard = Color(0xFF161616)
    val OfoxDark = Color(0xFF0A0A0A)
    val scope = rememberCoroutineScope()

    // Основные настройки
    var memoryCacheEnabled by remember { mutableStateOf(ImageCacheConfig.enableMemory) }
    var diskCacheEnabled by remember { mutableStateOf(ImageCacheConfig.enableDisk) }
    var memoryCachePercent by remember { mutableFloatStateOf((ImageCacheConfig.memoryCachePercent * 100).toFloat()) }
    var diskCacheSize by remember { mutableFloatStateOf((ImageCacheConfig.diskCacheSize / (1024f * 1024f))) }

    // Детальные настройки
    var cacheMessages by remember { mutableStateOf(ImageCacheConfig.cacheMessages) }
    var cacheGroups by remember { mutableStateOf(ImageCacheConfig.cacheGroupMessages) }
    var cacheNotifications by remember { mutableStateOf(ImageCacheConfig.cacheNotifications) }
    var cacheMedia by remember { mutableStateOf(ImageCacheConfig.cacheMedia) }
    var cacheAvatars by remember { mutableStateOf(ImageCacheConfig.cacheAvatars) }
    var cachePosts by remember { mutableStateOf(ImageCacheConfig.cachePosts) }

    // Размеры для каждого типа
    var messagesSize by remember { mutableFloatStateOf((ImageCacheConfig.messagesCacheSize / (1024f * 1024f))) }
    var groupsSize by remember { mutableFloatStateOf((ImageCacheConfig.groupCacheSize / (1024f * 1024f))) }
    var notificationsSize by remember { mutableFloatStateOf((ImageCacheConfig.notificationsCacheSize / (1024f * 1024f))) }
    var mediaSize by remember { mutableFloatStateOf((ImageCacheConfig.mediaCacheSize / (1024f * 1024f))) }
    var avatarsSize by remember { mutableFloatStateOf((ImageCacheConfig.avatarsCacheSize / (1024f * 1024f))) }
    var postsSize by remember { mutableFloatStateOf((ImageCacheConfig.postsCacheSize / (1024f * 1024f))) }

    // Состояние уведомлений
    var showSavedSnackbar by remember { mutableStateOf(false) }
    var showClearedSnackbar by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            ModernTopBar(
                title = "Управление кэшем",
                onBack = onBack,
                accentColor = OfoxOrange
            )
        },
        containerColor = OfoxDark,
        bottomBar = {
            BottomActionBar(
                onSave = {
                    // Сохранить все изменения
                    ImageCacheConfig.enableMemory = memoryCacheEnabled
                    ImageCacheConfig.enableDisk = diskCacheEnabled
                    ImageCacheConfig.memoryCachePercent = (memoryCachePercent / 100.0)
                    ImageCacheConfig.diskCacheSize = (diskCacheSize * 1024 * 1024).toLong()

                    ImageCacheConfig.cacheMessages = cacheMessages
                    ImageCacheConfig.cacheGroupMessages = cacheGroups
                    ImageCacheConfig.cacheNotifications = cacheNotifications
                    ImageCacheConfig.cacheMedia = cacheMedia
                    ImageCacheConfig.cacheAvatars = cacheAvatars
                    ImageCacheConfig.cachePosts = cachePosts

                    ImageCacheConfig.messagesCacheSize = (messagesSize * 1024 * 1024).toLong()
                    ImageCacheConfig.groupCacheSize = (groupsSize * 1024 * 1024).toLong()
                    ImageCacheConfig.notificationsCacheSize = (notificationsSize * 1024 * 1024).toLong()
                    ImageCacheConfig.mediaCacheSize = (mediaSize * 1024 * 1024).toLong()
                    ImageCacheConfig.avatarsCacheSize = (avatarsSize * 1024 * 1024).toLong()
                    ImageCacheConfig.postsCacheSize = (postsSize * 1024 * 1024).toLong()

                    onSave()
                    scope.launch {
                        showSavedSnackbar = true
                        delay(2000)
                        showSavedSnackbar = false
                    }
                },
                onClear = {
                    onClearCache()
                    scope.launch {
                        showClearedSnackbar = true
                        delay(2000)
                        showClearedSnackbar = false
                    }
                },
                accentColor = OfoxOrange,
                backgroundColor = OfoxCard
            )
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                // Описание
                InfoCard(
                    text = "Настройте параметры кэша для оптимизации производительности и управления памятью устройства.",
                    icon = Icons.Rounded.Info
                )

                // Основные настройки кэша
                Text(
                    "ОСНОВНЫЕ ПАРАМЕТРЫ",
                    color = OfoxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 8.dp)
                )

                MainCacheCard(
                    title = "Кэш в памяти",
                    description = "Быстрый доступ к данным в оперативной памяти",
                    icon = Icons.Rounded.Speed,
                    enabled = memoryCacheEnabled,
                    onEnabledChange = { memoryCacheEnabled = it },
                    sliderValue = memoryCachePercent,
                    onSliderChange = { memoryCachePercent = it },
                    min = 10f,
                    max = 50f,
                    unit = "%",
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                MainCacheCard(
                    title = "Кэш на диске",
                    description = "Долговременное хранение на внутреннем накопителе",
                    icon = Icons.Rounded.Storage,
                    enabled = diskCacheEnabled,
                    onEnabledChange = { diskCacheEnabled = it },
                    sliderValue = diskCacheSize,
                    onSliderChange = { diskCacheSize = it },
                    min = 128f,
                    max = 2048f,
                    unit = "МБ",
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                // Детальные настройки
                Text(
                    "ТИПЫ ДАННЫХ",
                    color = OfoxOrange,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 4.dp, top = 12.dp)
                )

                DetailedCacheCard(
                    title = "Сообщения",
                    icon = Icons.Rounded.Message,
                    enabled = cacheMessages,
                    onEnabledChange = { cacheMessages = it },
                    sliderValue = messagesSize,
                    onSliderChange = { messagesSize = it },
                    min = 32f,
                    max = 512f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                DetailedCacheCard(
                    title = "Групповые чаты",
                    icon = Icons.Rounded.Group,
                    enabled = cacheGroups,
                    onEnabledChange = { cacheGroups = it },
                    sliderValue = groupsSize,
                    onSliderChange = { groupsSize = it },
                    min = 32f,
                    max = 512f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                DetailedCacheCard(
                    title = "Уведомления",
                    icon = Icons.Rounded.Notifications,
                    enabled = cacheNotifications,
                    onEnabledChange = { cacheNotifications = it },
                    sliderValue = notificationsSize,
                    onSliderChange = { notificationsSize = it },
                    min = 16f,
                    max = 256f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                DetailedCacheCard(
                    title = "Медиафайлы",
                    icon = Icons.Rounded.Image,
                    enabled = cacheMedia,
                    onEnabledChange = { cacheMedia = it },
                    sliderValue = mediaSize,
                    onSliderChange = { mediaSize = it },
                    min = 128f,
                    max = 2048f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                DetailedCacheCard(
                    title = "Аватары",
                    icon = Icons.Rounded.AccountCircle,
                    enabled = cacheAvatars,
                    onEnabledChange = { cacheAvatars = it },
                    sliderValue = avatarsSize,
                    onSliderChange = { avatarsSize = it },
                    min = 32f,
                    max = 512f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                DetailedCacheCard(
                    title = "Посты",
                    icon = Icons.Rounded.Article,
                    enabled = cachePosts,
                    onEnabledChange = { cachePosts = it },
                    sliderValue = postsSize,
                    onSliderChange = { postsSize = it },
                    min = 64f,
                    max = 1024f,
                    cardColor = OfoxCard,
                    accentColor = OfoxOrange
                )

                Spacer(Modifier.height(16.dp))
            }

            // Snackbar для сохранения
            AnimatedVisibility(
                visible = showSavedSnackbar,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
            ) {
                SnackbarNotification(
                    message = "Настройки сохранены",
                    icon = Icons.Rounded.CheckCircle,
                    color = OfoxOrange
                )
            }

            // Snackbar для очистки
            AnimatedVisibility(
                visible = showClearedSnackbar,
                enter = slideInVertically() + fadeIn(),
                exit = slideOutVertically() + fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 90.dp)
            ) {
                SnackbarNotification(
                    message = "Кэш очищен",
                    icon = Icons.Rounded.Delete,
                    color = OfoxOrange
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModernTopBar(
    title: String,
    onBack: () -> Unit,
    accentColor: Color
) {
    TopAppBar(
        title = {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp
            )
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    tint = accentColor
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF161616)
        )
    )
}

@Composable
fun InfoCard(text: String, icon: ImageVector) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(24.dp)
            )
            Text(
                text,
                color = Color(0xFFB0B0B0),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )
        }
    }
}

@Composable
fun MainCacheCard(
    title: String,
    description: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    min: Float,
    max: Float,
    unit: String,
    cardColor: Color,
    accentColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(18.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(accentColor.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Text(
                        title,
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 16.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentColor,
                        checkedTrackColor = accentColor.copy(alpha = 0.4f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                description,
                color = Color(0xFF909090),
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(16.dp))
                    CompactSlider(
                        value = sliderValue,
                        onValueChange = onSliderChange,
                        valueRange = min..max,
                        accentColor = accentColor
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "${min.toInt()} $unit",
                            color = Color(0xFF707070),
                            fontSize = 11.sp
                        )
                        Text(
                            "${sliderValue.toInt()} $unit",
                            color = accentColor,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "${max.toInt()} $unit",
                            color = Color(0xFF707070),
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DetailedCacheCard(
    title: String,
    icon: ImageVector,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    sliderValue: Float,
    onSliderChange: (Float) -> Unit,
    min: Float,
    max: Float,
    cardColor: Color,
    accentColor: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = cardColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        icon,
                        contentDescription = null,
                        tint = if (enabled) accentColor else Color.Gray,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        title,
                        color = if (enabled) Color.White else Color.Gray,
                        fontWeight = FontWeight.Medium,
                        fontSize = 15.sp
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = accentColor,
                        checkedTrackColor = accentColor.copy(alpha = 0.4f),
                        uncheckedThumbColor = Color.Gray,
                        uncheckedTrackColor = Color.DarkGray
                    )
                )
            }

            AnimatedVisibility(visible = enabled) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    CompactSlider(
                        value = sliderValue,
                        onValueChange = onSliderChange,
                        valueRange = min..max,
                        accentColor = accentColor
                    )
                    Text(
                        "${sliderValue.toInt()} МБ",
                        color = accentColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.End)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompactSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    accentColor: Color
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        valueRange = valueRange,
        colors = SliderDefaults.colors(
            thumbColor = accentColor,
            activeTrackColor = accentColor,
            inactiveTrackColor = Color(0xFF2A2A2A)
        ),
        thumb = {
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(accentColor)
            )
        },
        track = { sliderState ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color(0xFF2A2A2A))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(sliderState.value / sliderState.valueRange.endInclusive)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(2.dp))
                        .background(accentColor)
                )
            }
        }
    )
}

@Composable
fun BottomActionBar(
    onSave: () -> Unit,
    onClear: () -> Unit,
    accentColor: Color,
    backgroundColor: Color
) {
    Surface(
        color = backgroundColor,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onClear,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = accentColor
                ),
                border = ButtonDefaults.outlinedButtonBorder.copy(
                    width = 1.5.dp,
                    brush = androidx.compose.ui.graphics.SolidColor(accentColor)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Rounded.Delete,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Очистить", fontWeight = FontWeight.SemiBold)
            }

            Button(
                onClick = onSave,
                colors = ButtonDefaults.buttonColors(containerColor = accentColor),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    Icons.Rounded.Save,
                    contentDescription = null,
                    tint = Color.Black,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("Сохранить", color = Color.Black, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SnackbarNotification(
    message: String,
    icon: ImageVector,
    color: Color
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        modifier = Modifier.padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(20.dp)
            )
            Text(
                message,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp
            )
        }
    }
}