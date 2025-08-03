package com.SkrinVex.OfoxMessenger.ui.common

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class CrashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Пользователь видит только стек ошибки
        val visibleError = intent.getStringExtra("error") ?: "Неизвестная ошибка"
        // Для отчёта в буфер копируем полный лог (со скрытой частью)
        val fullLog = intent.getStringExtra("full_log") ?: visibleError

        setContent {
            CrashScreen(
                visibleError = visibleError,
                fullLog = fullLog,
                onCopy = { copyToClipboard(fullLog) },
                onOpenLink = { openCrashSite() },
                onExit = { finishAffinity() }
            )
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Crash Report", text))
    }

    private fun openCrashSite() {
        val url = "https://ofox.greenchat.kz/crash/"
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        startActivity(intent)
    }
}

@Composable
fun CrashScreen(
    visibleError: String,
    fullLog: String,
    onCopy: () -> Unit,
    onOpenLink: () -> Unit,
    onExit: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.ErrorOutline,
                contentDescription = "Ошибка",
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(72.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Произошла ошибка",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Surface(
                color = Color(0xFF1E1E1E),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Чтобы мы могли исправить проблему:\n\n" +
                            "1. Нажмите «Скопировать ошибку».\n" +
                            "2. Перейдите по ссылке ниже.\n" +
                            "3. Вставьте ошибку в форму и отправьте отчёт.",
                    color = Color.White,
                    fontSize = 16.sp,
                    textAlign = TextAlign.Left,
                    modifier = Modifier.padding(16.dp)
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "ofox.skrinvex.su/crash/",
                color = Color(0xFF64B5F6),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable { onOpenLink() }
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = visibleError,
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF1E1E1E), shape = RoundedCornerShape(12.dp))
                    .padding(16.dp)
                    .heightIn(max = 280.dp)
                    .verticalScroll(rememberScrollState())
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onCopy,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B35)
                ),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Скопировать ошибку", color = Color.White)
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onExit) {
                Text("Закрыть приложение", color = Color(0xFFFF6B35))
            }
        }
    }
}
