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
import androidx.compose.ui.platform.LocalUriHandler
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.utils.HandleNotificationPermissionDialog
import kotlinx.coroutines.Dispatchers
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

    // Состояния для показа диалогов
    var showBlockedDialog by remember { mutableStateOf(false) }
    var showCorruptedDataDialog by remember { mutableStateOf(false) }

    // Анимации
    val infiniteTransition = rememberInfiniteTransition(label = "splash_animation")

    HandleNotificationPermissionDialog()

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

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val targetFile = File(context.filesDir, "ymv")
            if (!targetFile.exists()) {
                try {
                    context.assets.open("ymv").use { input ->
                        targetFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    Log.d("DeleteUser", "Файл ymv успешно скопирован в filesDir")
                } catch (e: Exception) {
                    Log.e("DeleteUser", "Ошибка копирования ymv: ${e.message}", e)
                }
            } else {
                Log.d("DeleteUser", "Файл ymv уже существует в filesDir")
            }
        }
    }

    // Функция для проверки корректности данных пользователя
    fun isUserDataValid(profile: Map<String, Any>?): Boolean {
        if (profile == null) return false

        val email = profile["email"]?.toString()
        val password = profile["password"]?.toString()

        if (email.isNullOrBlank()) return false
        if (password.isNullOrBlank()) return false

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

    suspend fun fetchRemoteAppConfig(): Pair<String, Int>? = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = URL("https://api.skrinvex.su/ofox.php")
            val conn = url.openConnection() as HttpURLConnection

            conn.apply {
                setRequestProperty("User-Agent", "OfoxChecker")
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                doInput = true
                useCaches = false
            }

            // Проверяем код ответа
            val responseCode = conn.responseCode
            Log.d("VersionCheck", "HTTP Response Code: $responseCode")

            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.e("VersionCheck", "HTTP error: $responseCode")
                conn.disconnect()
                return@withContext null
            }

            val response = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            Log.d("VersionCheck", "Server response: $response")

            val json = JSONObject(response)
            val version = json.getString("version")
            val build = json.getInt("build")

            Log.d("VersionCheck", "Remote version: $version, build: $build")
            Pair(version, build)

        } catch (e: Exception) {
            Log.e("VersionCheck", "Error fetching remote config: ${e.message}", e)
            null
        }
    }

    fun compareVersions(version1: String, version2: String): Int {
        val v1Parts = version1.split(".").map { it.toIntOrNull() ?: 0 }
        val v2Parts = version2.split(".").map { it.toIntOrNull() ?: 0 }

        val maxLength = maxOf(v1Parts.size, v2Parts.size)

        for (i in 0 until maxLength) {
            val v1Part = v1Parts.getOrNull(i) ?: 0
            val v2Part = v2Parts.getOrNull(i) ?: 0

            when {
                v1Part < v2Part -> return -1  // version1 меньше version2
                v1Part > v2Part -> return 1   // version1 больше version2
            }
        }
        return 0  // версии равны
    }

    // Функция для полного удаления поврежденного аккаунта
    suspend fun deleteCorruptedAccount(uid: String) {
        try {
            Log.d("DeleteUser", "Начало удаления аккаунта с uid: $uid")

            // Загружаем API-ключ из ymv файла
            val apiKey = withContext(Dispatchers.IO) {
                val ymvFile = File(context.filesDir, "ymv")
                if (!ymvFile.exists()) {
                    Log.e("DeleteUser", "Файл ymv не найден")
                    return@withContext null
                }

                val lines = ymvFile.readLines()
                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("API_SECRET_KEY=")) {
                        val key = trimmed.substringAfter("API_SECRET_KEY=").trim()
                        Log.d("DeleteUser", "API-ключ успешно считан")
                        return@withContext key
                    }
                }

                Log.e("DeleteUser", "API_SECRET_KEY не найден в ymv")
                null
            }

            if (apiKey.isNullOrEmpty()) {
                Log.e("DeleteUser", "API ключ пустой или отсутствует, отмена удаления")
                return
            }

            // Отправляем запрос на сервер для удаления аккаунта и из базы, и из Auth
            withContext(Dispatchers.IO) {
                Log.d("DeleteUser", "Отправка запроса на сервер delete_acc.php...")

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

                val response = conn.inputStream.bufferedReader().use { it.readText() }
                Log.d("DeleteUser", "Ответ сервера: $response")

                conn.disconnect()
            }

            Log.d("DeleteUser", "Удаление аккаунта завершено успешно")

        } catch (e: Exception) {
            Log.e("DeleteUser", "Ошибка при удалении аккаунта: ${e.message}", e)
        }
    }

    // Логика проверки и навигации
    LaunchedEffect(Unit) {
        delay(3000) // Минимальное время показа сплэша

        // Выполняем запрос в IO потоке
        val remote = fetchRemoteAppConfig()

        // Получаем локальную версию и билд
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val localVersion = packageInfo.versionName ?: "unknown"
        val localBuild = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            packageInfo.versionCode
        }

        Log.d("VersionCheck", "Local version: $localVersion, build: $localBuild")

        // Проверяем версии
        if (remote != null) {
            val (remoteVersion, remoteBuild) = remote
            Log.d("VersionCheck", "Comparing versions - Remote: $remoteVersion ($remoteBuild) vs Local: $localVersion ($localBuild)")

            // ИСПРАВЛЕННАЯ логика сравнения версий
            val versionMismatch = remoteVersion != localVersion
            val buildMismatch = remoteBuild > localBuild  // Только если удаленный билд НОВЕЕ

            if (versionMismatch || buildMismatch) {
                Log.d("VersionCheck", "Update required - Version mismatch: $versionMismatch, Build outdated: $buildMismatch")
                showForceUpdateDialog = true
                return@LaunchedEffect
            }

            Log.d("VersionCheck", "Version check passed")
        }

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

                        // Записываем дату последней активности
                        try {
                            withContext(Dispatchers.IO) {
                                FirebaseDatabase.getInstance()
                                    .getReference("users/$uid")
                                    .child("lastActivity")
                                    .setValue(System.currentTimeMillis())
                                    .await()
                                Log.d("LastActivity", "Last activity timestamp updated for uid: $uid")
                            }
                        } catch (e: Exception) {
                            Log.e("LastActivity", "Failed to update last activity: ${e.message}", e)
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