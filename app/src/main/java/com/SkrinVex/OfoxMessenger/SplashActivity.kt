package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Сразу проверяем интернет при запуске
        enableInternetCheck()
        enableEdgeToEdge()

        setContent {
            OfoxMessengerTheme {
                SplashScreen()
            }
        }
    }
}

@Composable
fun SplashScreen() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("session", Context.MODE_PRIVATE)
    val uid = prefs.getString("uid", null)

    // Анимации
    val infiniteTransition = rememberInfiniteTransition(label = "splash_animation")

    // Плавное вращение логотипа
    val logoRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(8000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "logo_rotation"
    )

    // Пульсация логотипа
    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    // Анимация орбитальных кругов
    val orbitalRotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(6000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbital_rotation"
    )

    // Анимация загрузочной полосы
    val progressAnimation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress_animation"
    )

    // Логика проверки и навигации
    LaunchedEffect(Unit) {
        delay(3000) // Минимальное время показа сплэша

        if (uid != null) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null && user.uid == uid) {
                    // Проверяем, заполнен ли профиль
                    val profileSnapshot = withContext(Dispatchers.IO) {
                        FirebaseDatabase.getInstance()
                            .getReference("users/$uid")
                            .get()
                            .await()
                    }
                    val profile = profileSnapshot.value as? Map<String, Any>

                    val intent = if (profile != null && profile.containsKey("username")) {
                        Intent(context, MainPageActivity::class.java).apply {
                            putExtra("uid", uid)
                        }
                    } else {
                        Intent(context, ProfileEditActivity::class.java).apply {
                            putExtra("uid", uid)
                        }
                    }

                    context.startActivity(intent)
                    (context as? ComponentActivity)?.finish()
                } else {
                    // Очищаем сессию и переходим к логину
                    prefs.edit().clear().apply()
                    val intent = Intent(context, MainActivity::class.java)
                    context.startActivity(intent)
                    (context as? ComponentActivity)?.finish()
                }
            } catch (e: Exception) {
                // При ошибке сети переходим к логину
                val intent = Intent(context, MainActivity::class.java)
                context.startActivity(intent)
                (context as? ComponentActivity)?.finish()
            }
        } else {
            // Нет сохраненной сессии, переходим к логину
            val intent = Intent(context, MainActivity::class.java)
            context.startActivity(intent)
            (context as? ComponentActivity)?.finish()
        }
    }

    // UI сплэша
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D0D0D),
                        Color(0xFF1A1A1A),
                        Color.Black
                    )
                )
            )
    ) {
        // Основной контейнер с карточкой
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Главная карточка с логотипом
            Card(
                modifier = Modifier
                    .size(280.dp)
                    .shadow(
                        elevation = 20.dp,
                        shape = RoundedCornerShape(32.dp),
                        ambientColor = Color(0xFFFF6B35).copy(alpha = 0.3f),
                        spotColor = Color(0xFFFF6B35).copy(alpha = 0.3f)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                ),
                shape = RoundedCornerShape(32.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Орбитальные кольца
                    repeat(3) { index ->
                        Box(
                            modifier = Modifier
                                .size((120 + index * 30).dp)
                                .rotate(orbitalRotation + (index * 120f))
                                .border(
                                    width = 2.dp,
                                    brush = Brush.sweepGradient(
                                        colors = listOf(
                                            Color.Transparent,
                                            Color(0xFFFF6B35).copy(alpha = 0.4f - index * 0.1f),
                                            Color.Transparent
                                        )
                                    ),
                                    shape = CircleShape
                                )
                        )
                    }

                    // Центральный логотип
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        // Пульсирующий фон логотипа
                        Box(
                            modifier = Modifier
                                .size(100.dp)
                                .scale(logoScale)
                                .background(
                                    Color(0xFFFF6B35).copy(alpha = 0.2f),
                                    CircleShape
                                )
                        )

                        // Логотип
                        Image(
                            painter = painterResource(id = R.drawable.logo),
                            contentDescription = "Logo",
                            modifier = Modifier
                                .size(80.dp)
                                .scale(logoScale)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Информационная карточка
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(
                        elevation = 12.dp,
                        shape = RoundedCornerShape(24.dp)
                    ),
                colors = CardDefaults.cardColors(
                    containerColor = Color(0xFF1A1A1A)
                ),
                shape = RoundedCornerShape(24.dp)
            ) {
                Column(
                    modifier = Modifier
                        .padding(24.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ofox Messenger",
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Общайся. Делись. Вдохновляйся.",
                        color = Color(0xFFFF6B35),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    // Кастомная загрузочная полоса
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .width(200.dp)
                                .height(4.dp)
                                .background(
                                    Color(0xFF333333),
                                    RoundedCornerShape(2.dp)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(progressAnimation)
                                    .height(4.dp)
                                    .background(
                                        Brush.horizontalGradient(
                                            colors = listOf(
                                                Color(0xFFFF6B35),
                                                Color(0xFFFF8A65)
                                            )
                                        ),
                                        RoundedCornerShape(2.dp)
                                    )
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Загрузка...",
                            color = Color.White.copy(alpha = 0.8f),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }

        // Нижняя карточка с копирайтом
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(24.dp)
                .shadow(
                    elevation = 8.dp,
                    shape = RoundedCornerShape(20.dp)
                ),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1A1A1A).copy(alpha = 0.9f)
            ),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .padding(16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Разработал",
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 12.sp
                )
                Text(
                    text = "SkrinVex",
                    color = Color(0xFFFF6B35),
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}