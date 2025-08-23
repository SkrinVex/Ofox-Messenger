package com.SkrinVex.OfoxMessenger.ui.common

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.lang.ref.WeakReference
import kotlin.math.abs

// Проверка интернета
private fun isInternetAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val network = cm.activeNetwork ?: return false
    val caps = cm.getNetworkCapabilities(network) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

// --- NoInternetOverlay (без изменений) ---
private class NoInternetOverlay(
    activity: ComponentActivity,
    private val onExit: () -> Unit
) {
    private val activityRef = WeakReference(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val overlay by lazy {
        ComposeView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setContent {
                NoInternetScreen(
                    onRetry = { /* обновится автоматически */ },
                    onExit = { onExit() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            handler.post { hide() }
        }

        override fun onLost(network: Network) {
            handler.post { show() }
        }
    }

    fun start() {
        val activity = activityRef.get() ?: return
        if (!isInternetAvailable(activity)) show()
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        hide()
    }

    private fun show() {
        val activity = activityRef.get() ?: return
        if (!activity.isDestroyed && !activity.isFinishing && overlay.parent == null) {
            activity.addContentView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun hide() {
        if (overlay.parent != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}

// --- WeakInternetOverlay ---
private class WeakInternetOverlay(activity: ComponentActivity) {
    private val activityRef = WeakReference(activity)
    private val handler = Handler(Looper.getMainLooper())
    private val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var isWeak = false

    private val overlay by lazy {
        ComposeView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            )
            setContent { WeakInternetBanner { isWeak } }
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, nc: NetworkCapabilities) {
            // Проверка: есть ли валидный интернет
            val validated = nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            // Скорость в Kbps (среднее значение вниз/вверх)
            val bandwidth = (nc.linkDownstreamBandwidthKbps + nc.linkUpstreamBandwidthKbps) / 2

            // Считаем интернет слабым если:
            // 1) интернет валидный
            // 2) скорость меньше 1000 Kbps (1 Мбит/с)
            val weak = validated && bandwidth in 1..1000

            if (weak != isWeak) {
                isWeak = weak
                handler.post { updateBanner() }
            }
        }

        override fun onLost(network: Network) {
            isWeak = false
            handler.post { updateBanner() }
        }
    }

    fun start() {
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (_: Exception) {}
    }

    fun stop() {
        try {
            cm.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        removeBanner()
    }

    private fun updateBanner() {
        val activity = activityRef.get() ?: return
        if (isWeak && overlay.parent == null) {
            activity.addContentView(
                overlay,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                )
            )
        } else if (!isWeak) {
            removeBanner()
        }
    }

    private fun removeBanner() {
        if (overlay.parent != null) {
            (overlay.parent as? ViewGroup)?.removeView(overlay)
        }
    }
}

@Composable
private fun WeakInternetBanner(isWeakProvider: () -> Boolean) {
    var visible by remember { mutableStateOf(isWeakProvider()) }
    var offset by remember { mutableStateOf(Offset(0f, 0f)) }
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(isWeakProvider()) {
        visible = isWeakProvider() && !dismissed
    }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(400)),
        exit = fadeOut(tween(400))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 80.dp)
                .offset { androidx.compose.ui.unit.IntOffset(offset.x.toInt(), offset.y.toInt()) }
                .pointerInput(Unit) {
                    detectDragGestures { _, dragAmount ->
                        offset += dragAmount
                    }
                }
                .background(Color(0xFFFFA726), RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Слабое подключение",
                    color = Color.Black,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { dismissed = true }) {
                    Text("Закрыть", color = Color.Black, fontSize = 12.sp)
                }
            }
        }
    }
}

// Подключение обоих оверлеев
fun ComponentActivity.enableInternetCheck() {
    val noInternetOverlay = NoInternetOverlay(this) { finish() }
    val weakInternetOverlay = WeakInternetOverlay(this)

    lifecycle.addObserver(object : androidx.lifecycle.DefaultLifecycleObserver {
        override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
            noInternetOverlay.start()
            weakInternetOverlay.start()
        }
        override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
            noInternetOverlay.stop()
            weakInternetOverlay.stop()
        }
    })
}

// Экран без интернета
@Composable
fun NoInternetScreen(
    onRetry: () -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF101010)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CloudOff,
                contentDescription = "Нет интернета",
                tint = Color(0xFFFF6B35),
                modifier = Modifier.size(80.dp)
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Нет подключения к интернету",
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Проверьте соединение и попробуйте снова.",
                color = Color.Gray,
                fontSize = 16.sp,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(48.dp))
            Button(
                onClick = onRetry,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "Повторить",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            TextButton(onClick = onExit) {
                Text(
                    text = "Выйти",
                    color = Color(0xFFFF6B35),
                    fontSize = 14.sp
                )
            }
        }
    }
}
