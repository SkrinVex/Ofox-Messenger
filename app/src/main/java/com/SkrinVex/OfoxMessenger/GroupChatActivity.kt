package com.SkrinVex.OfoxMessenger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.google.firebase.auth.FirebaseAuth

@OptIn(ExperimentalMaterial3Api::class)
class GroupChatActivity : ComponentActivity() {

    private lateinit var viewModel: GroupChatViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Add internet check like in ChatActivity
        enableInternetCheck()

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color(0xFF0A0A0A).toArgb()
        window.navigationBarColor = Color.Transparent.toArgb()

        val groupId = intent.getStringExtra("group_id") ?: return finish()
        val groupName = intent.getStringExtra("group_name") ?: "Группа"
        val groupPhoto = intent.getStringExtra("group_photo")
        val currentUser = FirebaseAuth.getInstance().currentUser

        if (currentUser == null) {
            android.util.Log.e("GroupChatActivity", "Пользователь не аутентифицирован, завершение активности")
            android.widget.Toast.makeText(this, "Требуется вход в аккаунт", android.widget.Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        // Fix: Use viewModels delegate properly
        viewModel = ViewModelProvider(
            this,
            GroupChatViewModelFactory(currentUser.uid, groupId)
        )[GroupChatViewModel::class.java]

        setContent {
            OfoxMessengerTheme {
                BaseChatScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    header = {
                        GroupTopBar(
                            groupName = groupName,
                            groupPhoto = groupPhoto,
                            status = viewModel.groupStatus.collectAsState().value,
                            onBack = { finish() }
                        )
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val groupId = intent.getStringExtra("group_id")
        if (groupId != null && ::viewModel.isInitialized) {
            viewModel.setInChat(groupId)
        }
    }

    override fun onPause() {
        super.onPause()
        if (::viewModel.isInitialized) {
            viewModel.setInChat(null)
            viewModel.clearTyping()
        }
    }
}

@Composable
fun GroupTopBar(
    groupName: String,
    groupPhoto: String?,
    status: GroupStatus,
    onBack: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .shadow(
                elevation = 8.dp,
                shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp)
            ),
        shape = RoundedCornerShape(bottomStart = 16.dp, bottomEnd = 16.dp),
        color = Color.Transparent
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFF1A1A1A),
                            Color(0xFF2A2A2A),
                            Color(0xFFFF6B35).copy(alpha = 0.2f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(40.dp)
                        .background(
                            Color(0xFFFF6B35).copy(alpha = 0.1f),
                            shape = CircleShape
                        )
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowBack,
                        contentDescription = "Назад",
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(groupPhoto?.takeIf { it.isNotBlank() })
                        .fallback(android.R.drawable.ic_menu_gallery)
                        .error(android.R.drawable.ic_menu_gallery)
                        .build(),
                    contentDescription = "Фото группы",
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(
                            brush = Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFFF6B35).copy(alpha = 0.3f),
                                    Color(0xFF333333)
                                )
                            )
                        ),
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = groupName,
                        color = Color.White,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.SemiBold
                    )

                    fun russianPlural(count: Int, one: String, few: String, many: String): String {
                        val c = count % 100
                        return if (c in 11..19) {
                            many
                        } else {
                            when (count % 10) {
                                1 -> one
                                2, 3, 4 -> few
                                else -> many
                            }
                        }
                    }

                    AnimatedContent(
                        targetState = status,
                        label = "group_status_anim"
                    ) { st ->
                        when {
                            st.typingUsers.isNotEmpty() -> {
                                TypingDotsGroup(st.typingUsers.joinToString(", "))
                            }

                            // если никого в сети — показываем количество участников группы вместо "Оффлайн"
                            st.onlineCount == 0 -> {
                                val count = st.memberCount.coerceAtLeast(0)
                                Text(
                                    text = "$count ${russianPlural(count, "участник", "участника", "участников")}",
                                    color = Color.Gray,
                                    fontSize = 13.sp
                                )
                            }

                            st.onlineCount == 1 -> Text(
                                text = "В сети: ${st.onlineNames.firstOrNull() ?: "1 участник"}",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )

                            st.onlineCount == 2 -> Text(
                                text = "В сети: ${st.onlineNames.take(2).joinToString(", ")}",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )

                            else -> Text(
                                text = "Онлайн ${st.onlineCount} ${russianPlural(st.onlineCount, "участник", "участника", "участников")}",
                                color = Color.Gray,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TypingDotsGroup(names: String) {
    val infiniteTransition = rememberInfiniteTransition(label = "dots")
    val scales = List(3) { i ->
        infiniteTransition.animateFloat(
            initialValue = 0.5f,
            targetValue = 1.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 400, delayMillis = i * 150, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dot$i"
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Печатает $names", color = Color.Gray, fontSize = 13.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Row {
            scales.forEach { scale ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .scale(scale.value)
                        .clip(CircleShape)
                        .background(Color.Gray)
                )
                Spacer(modifier = Modifier.width(2.dp))
            }
        }
    }
}