package com.SkrinVex.OfoxMessenger.games

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.SkrinVex.OfoxMessenger.R
import kotlinx.coroutines.delay
import kotlin.random.Random
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.tasks.await

class Flappy : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        val prefs = EncryptedSharedPreferences.create(
            "flappy_prefs",
            masterKeyAlias,
            this,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        setContent {
            FlappyGameScreen(prefs)
        }
    }
}

@Composable
fun FlappyGameScreen(prefs: android.content.SharedPreferences) {
    var gameState by remember { mutableStateOf(GameState.MENU) }
    var score by remember { mutableStateOf(0) }
    var bestScore by remember { mutableStateOf(prefs.getInt("best_score", 0)) }

    // 🔥 Подгружаем рекорд из Firebase при старте
    LaunchedEffect(Unit) {
        val uid = FirebaseAuth.getInstance().currentUser?.uid
        if (uid != null) {
            val snapshot = FirebaseDatabase.getInstance()
                .getReference("users/$uid/flappy/best_score")
                .get()
                .await()

            val remoteScore = snapshot.getValue(Int::class.java) ?: 0

            if (remoteScore > bestScore) {
                // Если в Firebase рекорд выше → обновляем локальный
                bestScore = remoteScore
                prefs.edit().putInt("best_score", bestScore).apply()
            } else if (bestScore > remoteScore) {
                // Если локальный выше → заливаем в Firebase
                FirebaseDatabase.getInstance()
                    .getReference("users/$uid/flappy/best_score")
                    .setValue(bestScore)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        when (gameState) {
            GameState.MENU -> {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Flappy Ofox",
                        color = Color(0xFFFF9800),
                        fontSize = 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(20.dp))
                    Text("Лучший счёт: $bestScore", color = Color.White)
                    Spacer(Modifier.height(40.dp))
                    Button(
                        onClick = {
                            score = 0
                            gameState = GameState.PLAYING
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Играть", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
            GameState.PLAYING -> {
                GameCanvas(
                    onGameOver = { finalScore ->
                        score = finalScore
                        if (finalScore > bestScore) {
                            bestScore = finalScore
                            prefs.edit().putInt("best_score", bestScore).apply()

                            // ✅ Сохраняем в Firebase
                            val uid = FirebaseAuth.getInstance().currentUser?.uid
                            if (uid != null) {
                                FirebaseDatabase.getInstance()
                                    .getReference("users/$uid/flappy")
                                    .child("best_score")
                                    .setValue(bestScore)
                            }
                        }
                        gameState = GameState.GAME_OVER
                    }
                )
            }
            GameState.GAME_OVER -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Игра окончена!", color = Color.Red, fontSize = 28.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("Счёт: $score", color = Color.White)
                    Text("Рекорд: $bestScore", color = Color(0xFFFF9800))
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = { gameState = GameState.MENU },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

enum class GameState {
    MENU, PLAYING, GAME_OVER
}

@Composable
fun GameCanvas(onGameOver: (score: Int) -> Unit) {
    val logoOriginal = ImageBitmap.imageResource(id = R.drawable.logo)

    var y by remember { mutableStateOf(500f) }
    var velocity by remember { mutableStateOf(0f) }
    var score by remember { mutableStateOf(0) }

    val playerWidth = 64f
    val playerHeight = 64f

    var gapSize by remember { mutableStateOf(300f) } // начальный размер разрыва между трубами
    var pipes by remember { mutableStateOf(listOf<Triple<Float, Int, Boolean>>()) } // x, gapY, passed

    LaunchedEffect(Unit) {
        while (true) {
            delay(16L) // ~60 FPS

            velocity += 0.5f
            y += velocity
            if (y < 0f) y = 0f

            // Создание новых труб
            if (pipes.isEmpty() || pipes.last().first < 600f) {
                pipes = pipes + Triple(1200f, Random.nextInt(200, 800), false)
            }

            // Движение труб и подсчёт очков
            pipes = pipes.map { pipe ->
                val (x, gapY, passed) = pipe
                val newX = x - 6
                var newPassed = passed
                // Начисление очка, если труба прошла игрока
                if (!passed && newX + 150f < 200f) {
                    score++
                    newPassed = true
                    if (score % 10 == 0 && gapSize > 120f) gapSize -= 10f
                }
                Triple(newX, gapY, newPassed)
            }.filter { it.first > -200 } // удаление труб за экраном

            // Проверка столкновений
            val playerRect = android.graphics.RectF(200f, y, 200f + playerWidth, y + playerHeight)
            for (pipe in pipes) {
                val gapY = pipe.second
                val pipeX = pipe.first
                val topRect = android.graphics.RectF(pipeX, 0f, pipeX + 150f, gapY.toFloat())
                val bottomRect = android.graphics.RectF(pipeX, gapY + gapSize, pipeX + 150f, 1600f)
                if (playerRect.intersect(topRect) || playerRect.intersect(bottomRect)) {
                    onGameOver(score)
                    return@LaunchedEffect
                }
            }
        }
    }

    Canvas(
        modifier = Modifier
            .fillMaxSize()
            .clickable { velocity = -10f }
    ) {
        // Игрок
        drawIntoCanvas { canvas ->
            val dstRect = android.graphics.RectF(200f, y, 200f + playerWidth, y + playerHeight)
            canvas.nativeCanvas.drawBitmap(
                logoOriginal.asAndroidBitmap(),
                null,
                dstRect,
                null
            )
        }

        // Трубы
        for (pipe in pipes) {
            val pipeX = pipe.first
            val gapY = pipe.second
            drawRect(
                color = Color(0xFFFF9800),
                topLeft = androidx.compose.ui.geometry.Offset(pipeX, 0f),
                size = androidx.compose.ui.geometry.Size(150f, gapY.toFloat())
            )
            drawRect(
                color = Color(0xFFFF9800),
                topLeft = androidx.compose.ui.geometry.Offset(pipeX, gapY + gapSize),
                size = androidx.compose.ui.geometry.Size(150f, size.height - gapY - gapSize)
            )
        }

        // Счёт
        drawContext.canvas.nativeCanvas.apply {
            drawText(
                "Счёт: $score",
                50f,
                100f,
                android.graphics.Paint().apply {
                    color = android.graphics.Color.WHITE
                    textSize = 60f
                    isFakeBoldText = true
                }
            )
        }
    }
}