package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
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
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalUriHandler
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.utils.HandleNotificationPermissionDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import kotlin.system.exitProcess

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
    var showForceUpdateDialog by remember { mutableStateOf(false) }
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showCorruptedDataDialog by remember { mutableStateOf(false) }

    // Упрощенные анимации для быстродействия
    val infiniteTransition = rememberInfiniteTransition(label = "splash_animation")

    val logoScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    val progressAnimation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress_animation"
    )

    HandleNotificationPermissionDialog()

    // Блокируем кнопку назад на сплэш-экране
    BackHandler {
        // Ничего не делаем, блокируем выход
    }

    // Копирование ymv файла в фоне
    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) {
            val targetFile = File(context.filesDir, "ymv")
            if (!targetFile.exists()) {
                try {
                    context.assets.open("ymv").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("DeleteUser", "Файл ymv успешно скопирован")
                } catch (e: Exception) {
                    Log.e("DeleteUser", "Ошибка копирования ymv: ${e.message}")
                }
            }
        }
    }

    // Функции валидации и очистки (без изменений)
    fun isUserDataValid(profile: Map<String, Any>?): Boolean {
        return profile?.let {
            !it["email"]?.toString().isNullOrBlank() &&
                    !it["password"]?.toString().isNullOrBlank()
        } ?: false
    }

    fun clearSessionAndGoToLogin() {
        prefs.edit().clear().apply()
        FirebaseAuth.getInstance().signOut()
        context.startActivity(Intent(context, MainActivity::class.java))
        (context as? ComponentActivity)?.finish()
    }

    // МАКСИМАЛЬНО БЫСТРАЯ функция проверки версии
    suspend fun fetchRemoteAppConfig(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        try {
            val url = URL("https://api.skrinvex.su/ofox.php")
            val conn = url.openConnection() as HttpURLConnection

            conn.apply {
                setRequestProperty("User-Agent", "OfoxChecker")
                connectTimeout = 2000  // Еще быстрее
                readTimeout = 2000     // Еще быстрее
                requestMethod = "GET"
                doInput = true
                useCaches = false
                instanceFollowRedirects = false
            }

            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return@withContext null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val json = JSONObject(response)
            Pair(json.getString("version"), json.getInt("build"))
        } catch (e: Exception) {
            null // Просто возвращаем null без логирования для скорости
        }
    }

    // СУПЕР БЫСТРАЯ функция удаления аккаунта
    suspend fun deleteCorruptedAccount(uid: String) {
        try {
            val apiKey = withContext(Dispatchers.IO) {
                File(context.filesDir, "ymv").takeIf { it.exists() }
                    ?.readLines()
                    ?.find { it.trim().startsWith("API_SECRET_KEY=") }
                    ?.substringAfter("API_SECRET_KEY=")?.trim()
            } ?: return

            withContext(Dispatchers.IO) {
                val conn = URL("https://api.skrinvex.su/delete_acc.php").openConnection() as HttpURLConnection
                conn.apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 2000
                    readTimeout = 2000
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                }

                conn.outputStream.use {
                    it.write("uid=${URLEncoder.encode(uid, "UTF-8")}&api_key=${URLEncoder.encode(apiKey, "UTF-8")}".toByteArray())
                }
                conn.inputStream.close() // Просто закрываем не читая ответ для скорости
                conn.disconnect()
            }
        } catch (e: Exception) {
            // Игнорируем ошибки для скорости
        }
    }

    // ГЛАВНАЯ ОПТИМИЗАЦИЯ - параллельные проверки
    LaunchedEffect(Unit) {
        // Минимальная задержка только 1.5 секунды вместо 3
        delay(1500)

        // Параллельно запускаем проверку версии и проверку пользователя
        val versionCheckJob = async(Dispatchers.IO) { fetchRemoteAppConfig() }
        val userCheckJob = async(Dispatchers.IO) {
            if (uid == null) return@async null

            val user = FirebaseAuth.getInstance().currentUser
            if (user?.uid != uid) return@async null

            try {
                // Одновременно получаем данные пользователя и обновляем активность
                val userDataDeferred = async {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid")
                        .get()
                        .await()
                }

                val updateActivityDeferred = async {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid/lastActivity")
                        .setValue(System.currentTimeMillis())
                        .await()
                }

                val userSnapshot = userDataDeferred.await()
                updateActivityDeferred.await()

                userSnapshot.value as? Map<String, Any>
            } catch (e: Exception) {
                throw e
            }
        }

        // Проверяем версию (не блокируем основной поток)
        try {
            val remote = versionCheckJob.await()
            if (remote != null) {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                val localVersion = packageInfo.versionName ?: "unknown"
                val localBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    packageInfo.longVersionCode.toInt()
                } else {
                    @Suppress("DEPRECATION") packageInfo.versionCode
                }

                val (remoteVersion, remoteBuild) = remote
                if (remoteVersion != localVersion || remoteBuild > localBuild) {
                    showForceUpdateDialog = true
                    return@LaunchedEffect
                }
            }
        } catch (e: Exception) {
            // Игнорируем ошибки проверки версии для ускорения
            Log.w("VersionCheck", "Проверка версии пропущена: ${e.message}")
        }

        // Проверяем пользователя
        try {
            val profile = userCheckJob.await()

            if (profile == null) {
                clearSessionAndGoToLogin()
                return@LaunchedEffect
            }

            if (!isUserDataValid(profile)) {
                launch { deleteCorruptedAccount(uid!!) }
                prefs.edit().clear().apply()
                FirebaseAuth.getInstance().signOut()
                showCorruptedDataDialog = true
                return@LaunchedEffect
            }

            // Переход к нужному активити
            val intent = if (profile.containsKey("username")) {
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
            when {
                dbException.message?.contains("Permission denied", true) == true ||
                        dbException.message?.contains("PERMISSION_DENIED", true) == true -> {
                    prefs.edit().clear().apply()
                    FirebaseAuth.getInstance().signOut()
                    showBlockedDialog = true
                }
                else -> clearSessionAndGoToLogin()
            }
        }
    }

    // УПРОЩЕННЫЙ UI для быстрой отрисовки
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(Color(0xFF0D0D0D), Color(0xFF1A1A1A))
                )
            )
    ) {
        // Основной контент строго по центру
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .systemBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Упрощенная карточка логотипа
            Card(
                modifier = Modifier
                    .size(260.dp)
                    .shadow(16.dp, RoundedCornerShape(28.dp)),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(28.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    // Центральный логотип без сложных анимаций
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

            Spacer(modifier = Modifier.height(32.dp))

            // Упрощенная информационная карточка
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Ofox Messenger",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Загружается...",
                        color = Color(0xFFFF6B35),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // Простая загрузочная полоса
                    Box(
                        modifier = Modifier
                            .width(180.dp)
                            .height(3.dp)
                            .background(Color(0xFF333333), RoundedCornerShape(2.dp)),
                        contentAlignment = Alignment.CenterStart
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressAnimation)
                                .height(3.dp)
                                .background(Color(0xFFFF6B35), RoundedCornerShape(2.dp))
                        )
                    }
                }
            }
        }

        // Упрощенный копирайт
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1A1A1A)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Text(
                text = "SkrinVex",
                color = Color(0xFFFF6B35),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(16.dp)
            )
        }
    }

    // Красивый диалог блокировки аккаунта
    if (showBlockedDialog) {
        val uriHandler = LocalUriHandler.current
        val supportUrl = "https://ofox.skrinvex.su/support/"

        // Создаем аннотированный текст для кнопки поддержки
        val annotatedSupportText = buildAnnotatedString {
            pushStringAnnotation(tag = "URL", annotation = supportUrl)
            withStyle(
                style = SpanStyle(
                    color = Color(0xFFFF6B35),
                    textDecoration = TextDecoration.Underline,
                    fontWeight = FontWeight.Bold
                )
            ) {
                append("Обратиться в поддержку")
            }
            pop()
        }

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
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        uriHandler.openUri(supportUrl)
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
                    ClickableText(
                        text = annotatedSupportText,
                        style = androidx.compose.ui.text.TextStyle(
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        ),
                        onClick = { offset ->
                            annotatedSupportText.getStringAnnotations(
                                tag = "URL",
                                start = offset,
                                end = offset
                            ).firstOrNull()?.let { annotation ->
                                uriHandler.openUri(annotation.item)
                            }
                        }
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
                                text = "Если считаете это ошибкой, используйте кнопку ниже для связи с поддержкой.",
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

    // Диалог о необходимости обновления приложения
    if (showForceUpdateDialog) {
        val uriHandler = LocalUriHandler.current

        AlertDialog(
            onDismissRequest = {},
            confirmButton = {
                Button(
                    onClick = {
                        uriHandler.openUri("https://ofox.skrinvex.su")
                        (context as? ComponentActivity)?.finishAffinity()
                        exitProcess(0)
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Скачать новую версию",
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
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFF6B35),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text(
                        text = "Требуется обновление",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            text = {
                Text(
                    text = "Вы используете устаревшую или неподдерживаемую версию приложения. Пожалуйста, скачайте последнюю версию.",
                    color = Color.White.copy(alpha = 0.9f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
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