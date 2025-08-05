package com.SkrinVex.OfoxMessenger

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.SkrinVex.OfoxMessenger.network.Friend
import com.SkrinVex.OfoxMessenger.network.FriendsResponse
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import com.google.firebase.auth.FirebaseAuth

class FriendsActivity : ComponentActivity() {
    private lateinit var viewModel: FriendsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()
        val uid = intent.getStringExtra("uid") ?: return finish()
        viewModel = ViewModelProvider(this, FriendsViewModelFactory(uid))[FriendsViewModel::class.java]

        setContent {
            OfoxMessengerTheme {
                FriendsScreen(
                    viewModel = viewModel,
                    onBack = { finish() },
                    onFriendClick = { friend ->
                        val intent = Intent(this, ProfileViewActivity::class.java)
                        intent.putExtra("uid", uid)
                        intent.putExtra("friend_uid", friend.id)
                        startActivity(intent)
                    }
                )
            }
        }

        val currentUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentUid == null || currentUid != uid) {
            showAuthErrorDialog(this, currentUid, uid)
            return
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.loadFriends()
    }
}

@Composable
fun FriendsScreen(
    viewModel: FriendsViewModel,
    onBack: () -> Unit,
    onFriendClick: (Friend) -> Unit
) {
    val state by viewModel.state.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf("friends") } // "friends" или "users"

    BackHandler { onBack() }

    Scaffold(
        topBar = { RoundedTopBar(title = "Поиск", onBack = onBack) },
        containerColor = Color(0xFF101010)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
        ) {
            SearchBar(
                query = searchQuery,
                onQueryChange = {
                    searchQuery = it
                    viewModel.searchFriends(it)
                }
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .fillMaxWidth(0.9f)
                        .height(52.dp)
                ) {
                    listOf(
                        "friends" to "Друзья",
                        "users" to "Все пользователи"
                    ).forEachIndexed { index, (value, label) ->
                        val isSelected = selectedTab == value
                        val shape = when (index) {
                            0 -> RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
                            1 -> RoundedCornerShape(topEnd = 16.dp, bottomEnd = 16.dp)
                            else -> RoundedCornerShape(0.dp)
                        }

                        SegmentedButton(
                            selected = isSelected,
                            onClick = { selectedTab = value },
                            shape = shape,
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = Color(0xFFFF6B35),
                                inactiveContainerColor = Color(0xFF1E1E1E),
                                activeContentColor = Color.White,
                                inactiveContentColor = Color.White.copy(alpha = 0.7f)
                            ),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = label,
                                fontSize = if (label.length > 12) 14.sp else 16.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                softWrap = false,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }

            when {
                state.isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFF6B35))
                    }
                }
                state.friendsData != null -> {
                    val friendsData = state.friendsData
                    val friends = friendsData?.friends ?: emptyList()
                    val otherUsers = friendsData?.other_users ?: emptyList()
                    val displayList = if (selectedTab == "friends") friends else otherUsers
                    val count = if (selectedTab == "friends") friendsData?.friends_count ?: 0 else friendsData?.other_users_count ?: 0

                    LazyColumn(
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = if (selectedTab == "friends") "Друзья ($count)" else "Все пользователи ($count)",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        if (displayList.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = if (searchQuery.isEmpty()) {
                                            if (selectedTab == "friends") "У вас пока нет друзей" else "Пользователи не найдены"
                                        } else {
                                            if (selectedTab == "friends") "Друзья не найдены" else "Пользователи не найдены"
                                        },
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 16.sp
                                    )
                                }
                            }
                        } else {
                            items(displayList) { user ->
                                FriendCard(user = user, onClick = { onFriendClick(user) })
                            }
                        }
                    }
                }
                else -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Не удалось загрузить данные: ${state.error ?: "Неизвестная ошибка"}",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        placeholder = { Text("Поиск пользователей...", color = Color.Gray) },
        leadingIcon = {
            Icon(Icons.Rounded.Search, contentDescription = "Поиск", tint = Color(0xFFFF6B35))
        },
        shape = RoundedCornerShape(16.dp),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Color(0xFF1E1E1E),
            unfocusedContainerColor = Color(0xFF1E1E1E),
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            focusedIndicatorColor = Color(0xFFFF6B35),
            unfocusedIndicatorColor = Color(0xFF333333),
            cursorColor = Color(0xFFFF6B35)
        ),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { /* Handle search */ })
    )
}

@Composable
fun FriendCard(user: Friend, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AsyncImage(
                model = if (user.profile_photo?.isNotBlank() == true) "https://api.skrinvex.su${user.profile_photo}" else null,
                contentDescription = "Фото пользователя",
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333)),
                contentScale = ContentScale.Crop,
                placeholder = painterResource(id = R.drawable.logo),
                error = painterResource(id = R.drawable.logo)
            )
            Column {
                Text(
                    text = user.nickname ?: user.username,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
                SmartLinkText(
                    text = user.status ?: "Статус не указан",
                    color = Color.Gray,
                    fontSize = 14.sp
                )
            }
        }
    }
}