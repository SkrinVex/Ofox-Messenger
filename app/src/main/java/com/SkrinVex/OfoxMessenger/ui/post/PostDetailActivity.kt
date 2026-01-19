package com.SkrinVex.OfoxMessenger.ui.post

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import com.SkrinVex.OfoxMessenger.PostsViewModel
import com.SkrinVex.OfoxMessenger.PostsViewModelFactory
import com.SkrinVex.OfoxMessenger.ui.common.MorphLoadingIndicator
import com.SkrinVex.OfoxMessenger.ui.dialogs.GlobalDialogHost
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.PostCard

class PostDetailActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uid = intent.getStringExtra(EXTRA_UID) ?: ""
        val postId = intent.getStringExtra(EXTRA_POST_ID) ?: ""

        val viewModel: PostsViewModel by viewModels { PostsViewModelFactory(uid) }

        setContent {
            OfoxMessengerTheme {
                PostDetailScreen(
                    uid = uid,
                    postId = postId,
                    viewModel = viewModel,
                    onBack = { finish() }
                )
                GlobalDialogHost()
            }
        }
    }

    companion object {
        private const val EXTRA_UID = "uid"
        private const val EXTRA_POST_ID = "post_id"

        fun start(context: Context, uid: String, postId: String) {
            val intent = Intent(context, PostDetailActivity::class.java).apply {
                putExtra(EXTRA_UID, uid)
                putExtra(EXTRA_POST_ID, postId)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
private fun PostDetailScreen(
    uid: String,
    postId: String,
    viewModel: PostsViewModel,
    onBack: () -> Unit
) {
    val state by viewModel.state.collectAsState()
    val post = state.posts.firstOrNull { it.id == postId }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF101010))
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White
                )
            }
            Text(
                text = "Пост",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        }

        if (post == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (state.isLoading) {
                    MorphLoadingIndicator()
                } else {
                    Text(
                        text = "Пост не найден",
                        color = Color.White.copy(alpha = 0.8f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            // Reuse same post card (comments/likes/etc still work).
            // Disable opening nested detail from inside itself.
            PostCard(
                post = post,
                currentUid = uid,
                viewModel = viewModel,
                onOpenPost = { /* no-op */ }
            )
        }
    }
}

