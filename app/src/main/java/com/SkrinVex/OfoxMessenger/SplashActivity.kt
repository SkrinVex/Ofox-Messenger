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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Error
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
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextDecoration
import android.net.Uri
import android.util.Log
import androidx.compose.ui.platform.LocalUriHandler
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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

    // Состояния для показа диалогов
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showCorruptedDataDialog by remember { mutableStateOf(false) }

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

    // Функция для проверки корректности данных пользователя
    fun isUserDataValid(profile: Map<String, Any>?): Boolean {
        if (profile == null) return false

        // Проверяем обязательные поля
        val email = profile["email"] as? String
        val password = profile["password"] as? String
        val createdAt = profile["created_at"] as? String

        // Email обязателен и не должен быть пустым
        if (email.isNullOrEmpty()) return false

        // Password обязателен и не должен быть пустым
        if (password.isNullOrEmpty()) return false

        // created_at обязателен и не должен быть пустым
        if (createdAt.isNullOrEmpty()) return false

        // username не обязателен, так как аккаунт может быть только создан

        return true
    }

    // Функция для очистки сессии и перехода к логину
    fun clearSessionAndGoToLogin() {
        prefs.edit().clear().apply()
        FirebaseAuth.getInstance().signOut()
        val intent = Intent(context, MainActivity::class.java)
        context.startActivity(intent)
        (context as? ComponentActivity)?.finish()
    }

    // Функция для полного удаления поврежденного аккаунта
    suspend fun deleteCorruptedAccount(uid: String) {
        try {
            // Загружаем API-ключ из .ymv файла
            val apiKey = withContext(Dispatchers.IO) {
                val ymvFile = File(".ymv")
                if (!ymvFile.exists()) return@withContext null

                val lines = ymvFile.readLines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("API_SECRET_KEY=")) {
                        return@withContext trimmed.substringAfter("API_SECRET_KEY=").trim()
                    }
                }
                null
            }

            if (apiKey.isNullOrEmpty()) return

            // Удаляем данные пользователя из Realtime Database
            withContext(Dispatchers.IO) {
                FirebaseDatabase.getInstance()
                    .getReference("users/$uid")
                    .removeValue()
                    .await()
            }

            // Отправляем запрос на сервер для удаления аккаунта из Firebase Authentication
            withContext(Dispatchers.IO) {
                val url = URL("https://api.skrinvex.su/delete_acc.php")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")

                val postData = "uid=" + URLEncoder.encode(uid, "UTF-8") +
                        "&api_key=" + URLEncoder.encode(apiKey, "UTF-8")

                val writer = OutputStreamWriter(conn.outputStream)
                writer.write(postData)
                writer.flush()
                writer.close()

                conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
            }
        } catch (_: Exception) {
            // Ошибки игнорируются по просьбе
        }
    }

    // Логика проверки и навигации
    LaunchedEffect(Unit) {
        delay(3000) // Минимальное время показа сплэша

        if (uid != null) {
            try {
                val user = FirebaseAuth.getInstance().currentUser
                if (user != null && user.uid == uid) {
                    try {
                        // Получаем данные пользователя из базы данных
                        val userSnapshot = withContext(Dispatchers.IO) {
                            FirebaseDatabase.getInstance()
                                .getReference("users/$uid")
                                .get()
                                .await()
                        }

                        val profile = userSnapshot.value as? Map<String, Any>

                        // Проверяем корректность данных пользователя
                        if (!isUserDataValid(profile)) {
                            // Данные пользователя некорректны - удаляем аккаунт полностью
                            launch {
                                deleteCorruptedAccount(uid)
                            }
                            prefs.edit().clear().apply()
                            FirebaseAuth.getInstance().signOut()
                            showCorruptedDataDialog = true
                            return@LaunchedEffect
                        }

                        // Дополнительная проверка доступа к профилю
                        val testSnapshot = withContext(Dispatchers.IO) {
                            FirebaseDatabase.getInstance()
                                .getReference("users/$uid/profile")
                                .get()
                                .await()
                        }

                        // Данные корректны, определяем куда переходить
                        val intent = if (profile!!.containsKey("username")) {
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

                    } catch (dbException: Exception) {
                        // Если получили ошибку доступа к базе данных - скорее всего пользователь заблокирован
                        when {
                            dbException.message?.contains("Permission denied", true) == true ||
                                    dbException.message?.contains("Database access denied", true) == true ||
                                    dbException.message?.contains("Access denied", true) == true ||
                                    dbException.message?.contains("PERMISSION_DENIED", true) == true -> {
                                // Пользователь заблокирован - очищаем сессию и показываем диалог
                                prefs.edit().clear().apply()
                                FirebaseAuth.getInstance().signOut()
                                showBlockedDialog = true
                                return@LaunchedEffect
                            }
                            else -> {
                                // Другая ошибка базы данных - переходим к логину
                                clearSessionAndGoToLogin()
                            }
                        }
                    }
                } else {
                    // Очищаем сессию и переходим к логину
                    clearSessionAndGoToLogin()
                }
            } catch (e: Exception) {
                // При ошибке сети переходим к логину
                clearSessionAndGoToLogin()
            }
        } else {
            // Нет сохраненной сессии, переходим к логину
            clearSessionAndGoToLogin()
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
        // Основной контайнер с карточкой
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

    // Красивый диалог блокировки аккаунта
    if (showBlockedDialog) {
        AlertDialog(
            onDismissRequest = { /* Не позволяем закрыть диалог */ },
            confirmButton = {
                Button(
                    onClick = {
                        showBlockedDialog = false
                        (context as? ComponentActivity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Понятно",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Color(0xFFFF6B35).copy(alpha = 0.2f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Block,
                            contentDescription = null,
                            tint = Color(0xFFFF6B35),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Аккаунт заблокирован",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Text(
                        text = "Ваш аккаунт был заблокирован администратором.",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A2A2A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Если считаете это ошибкой, обратитесь в службу поддержки",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(20.dp),
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            modifier = Modifier.shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(20.dp)
            )
        )
    }

    // Красивый диалог поврежденных данных
    if (showCorruptedDataDialog) {
        val uriHandler = LocalUriHandler.current
        val registrationUrl = "https://api.skrinvex.su/auth/"

        // Создаем аннотированный текст со ссылкой
        val annotatedText = buildAnnotatedString {
            append("Обнаружены поврежденные или неполные данные аккаунта. Пожалуйста, создайте ваш аккаунт заново.\n\nСсылка для регистрации: ")

            pushStringAnnotation(tag = "URL", annotation = registrationUrl)
            withStyle(
                style = SpanStyle(
                    color = Color(0xFFFF6B35),
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append(registrationUrl)
            }
            pop()
        }

        AlertDialog(
            onDismissRequest = { /* Не позволяем закрыть диалог */ },
            confirmButton = {
                Button(
                    onClick = {
                        showCorruptedDataDialog = false
                        uriHandler.openUri(registrationUrl)
                        (context as? ComponentActivity)?.finish()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Зарегистрироваться",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showCorruptedDataDialog = false
                        (context as? ComponentActivity)?.finish()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = Color(0xFFFF6B35)
                    ),
                    border = ButtonDefaults.outlinedButtonBorder.copy(
                        brush = Brush.horizontalGradient(
                            colors = listOf(Color(0xFFFF6B35), Color(0xFFFF8A65))
                        )
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Закрыть",
                        fontWeight = FontWeight.Medium,
                        fontSize = 16.sp
                    )
                }
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .background(
                                Color(0xFFFF6B35).copy(alpha = 0.2f),
                                CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color(0xFFFF6B35),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Аккаунт поврежден",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    ClickableText(
                        text = annotatedText,
                        style = androidx.compose.ui.text.TextStyle(
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 16.sp,
                            lineHeight = 24.sp
                        ),
                        onClick = { offset ->
                            annotatedText.getStringAnnotations(
                                tag = "URL",
                                start = offset,
                                end = offset
                            ).firstOrNull()?.let { annotation ->
                                uriHandler.openUri(annotation.item)
                            }
                        }
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color(0xFF2A2A2A)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Warning,
                                contentDescription = null,
                                tint = Color(0xFFFFB74D),
                                modifier = Modifier.size(20.dp)
                            )
                            Text(
                                text = "Поврежденный аккаунт был автоматически удален для безопасности",
                                color = Color.White.copy(alpha = 0.8f),
                                fontSize = 14.sp,
                                lineHeight = 20.sp
                            )
                        }
                    }
                }
            },
            containerColor = Color(0xFF1A1A1A),
            shape = RoundedCornerShape(20.dp),
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false
            ),
            modifier = Modifier.shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(20.dp)
            )
        )
    }
}