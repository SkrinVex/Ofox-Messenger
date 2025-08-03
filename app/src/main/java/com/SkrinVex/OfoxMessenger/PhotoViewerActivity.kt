package com.SkrinVex.OfoxMessenger.ui.viewer

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import coil.compose.AsyncImage
import kotlin.math.abs

class PhotoViewerActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_URL = "extra_image_url"
        fun start(context: Context, url: String) {
            val intent = Intent(context, PhotoViewerActivity::class.java).apply {
                putExtra(EXTRA_URL, url)
            }
            context.startActivity(intent)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Включаем edge-to-edge для Android 15
        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val imageUrl = intent.getStringExtra(EXTRA_URL) ?: return finish()
        setContent {
            PhotoViewerScreen(imageUrl) { finish() }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoViewerScreen(
    imageUrl: String,
    onClose: () -> Unit
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }
    var isLoaded by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val screenWidth = configuration.screenWidthDp.dp
    val screenHeight = configuration.screenHeightDp.dp

    val doubleTapZoom = 2.5f
    val maxZoom = 8f

    // Анимация для появления элементов
    val slideInAnimation by animateFloatAsState(
        targetValue = if (isLoaded) 0f else -100f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        )
    )

    val fadeInAnimation by animateFloatAsState(
        targetValue = if (isLoaded) 1f else 0f,
        animationSpec = tween(1000)
    )

    // Цветовая схема
    val primaryOrange = Color(0xFFFF6B35)
    val darkOrange = Color(0xFFE55100)
    val deepBlack = Color(0xFF0D0D0D)
    val softBlack = Color(0xFF1A1A1A)
    val accentOrange = Color(0xFFFF8A50)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(
                        softBlack,
                        deepBlack,
                        Color.Black
                    ),
                    radius = screenWidth.value * 0.8f
                )
            )
            .windowInsetsPadding(WindowInsets.statusBars) // Поддержка edge-to-edge
    ) {
        // Размытый фоновый слой
        Box(
            modifier = Modifier
                .fillMaxSize()
                .blur(25.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryOrange.copy(alpha = 0.1f),
                            darkOrange.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                )
        )

        // Основной контент
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Material 3 стильный TopAppBar с закруглениями
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .shadow(
                        elevation = 8.dp,
                        shape = RoundedCornerShape(24.dp),
                        spotColor = primaryOrange.copy(alpha = 0.3f)
                    )
                    .graphicsLayer {
                        translationY = slideInAnimation
                        alpha = fadeInAnimation
                    },
                shape = RoundedCornerShape(24.dp),
                color = softBlack.copy(alpha = 0.95f),
                border = BorderStroke(1.dp, primaryOrange.copy(alpha = 0.3f))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            brush = Brush.horizontalGradient(
                                colors = listOf(
                                    softBlack.copy(alpha = 0.95f),
                                    deepBlack.copy(alpha = 0.9f)
                                )
                            ),
                            shape = RoundedCornerShape(24.dp)
                        )
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        // Заголовок с градиентом
                        Text(
                            text = "Просмотр фото",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            style = LocalTextStyle.current.copy(
                                brush = Brush.linearGradient(
                                    colors = listOf(primaryOrange, accentOrange)
                                )
                            )
                        )

                        // Стильная кнопка закрытия
                        Surface(
                            onClick = onClose,
                            shape = RoundedCornerShape(16.dp),
                            color = primaryOrange.copy(alpha = 0.2f),
                            border = BorderStroke(1.dp, primaryOrange.copy(alpha = 0.5f)),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Закрыть",
                                    tint = primaryOrange,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Основная область для фото
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                // Изображение с границами, которые привязаны к нему (как в Telegram)
                Box(
                    modifier = Modifier
                        .graphicsLayer {
                            alpha = fadeInAnimation
                            scaleX = scale
                            scaleY = scale
                            translationX = offsetX
                            translationY = offsetY
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onDoubleTap = {
                                    val targetScale = if (scale < doubleTapZoom) doubleTapZoom else 1f
                                    scale = targetScale
                                    if (targetScale == 1f) {
                                        offsetX = 0f
                                        offsetY = 0f
                                    }
                                }
                            )
                        }
                        .pointerInput(Unit) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                val newScale = (scale * zoom).coerceIn(1f, maxZoom)
                                scale = newScale

                                // Свободное перемещение без ограничений
                                offsetX += pan.x
                                offsetY += pan.y
                            }
                        }
                ) {
                    // Изображение без фильтров и оранжевых зон
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Просмотр изображения",
                        contentScale = ContentScale.Fit,
                        onSuccess = { isLoaded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(0.dp))
                            .background(Color.Transparent) // Убираем любые цветные фоны
                    )

                    // Тонкие чистые границы без внутренних эффектов
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .border(
                                width = 0.5.dp,
                                color = primaryOrange.copy(alpha = 0.7f), // Простой цвет вместо градиента
                                shape = RoundedCornerShape(0.dp)
                            )
                    )
                }

                // Индикатор масштаба
                if (scale > 1f) {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = softBlack.copy(alpha = 0.8f),
                        border = BorderStroke(1.dp, primaryOrange.copy(alpha = 0.3f)),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 32.dp)
                    ) {
                        Text(
                            text = "${(scale * 100).toInt()}%",
                            color = primaryOrange,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}