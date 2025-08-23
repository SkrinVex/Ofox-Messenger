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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import coil.compose.AsyncImage
import com.SkrinVex.OfoxMessenger.network.Friend
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import com.SkrinVex.OfoxMessenger.utils.SmartLinkText
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class FriendsActivity : ComponentActivity() {
    private lateinit var viewModel: FriendsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()

        val uid = intent.getStringExtra("uid") ?: return finish()
        viewModel = ViewModelProvider(this, FriendsViewModelFactory(uid))[FriendsViewModel::class.java]

        // теперь можно грузить друзей
        viewModel.loadFriends()

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
    var selectedTab by remember { mutableStateOf("friends") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }

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
                onQueryChange = { newQuery ->
                    searchQuery = newQuery
                    selectedTab = if (newQuery.isNotBlank()) "users" else "friends"

                    searchJob?.cancel()
                    searchJob = coroutineScope.launch {
                        delay(350) // задержка для debounce
                        viewModel.searchFriends(newQuery)
                    }
                }
            )

            // табы (если нужно)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                // можешь сделать SegmentedButtons: "Друзья" / "Все пользователи"
            }

            when {
                state.isLoading && state.friends.isEmpty() && state.users.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFFFF6B35))
                    }
                }

                state.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "Не удалось загрузить данные: ${state.error}",
                            color = Color.White,
                            fontSize = 16.sp
                        )
                    }
                }

                else -> {
                    val displayList = if (selectedTab == "friends") state.friends else state.users
                    val count = displayList.size

                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            Text(
                                text = if (selectedTab == "friends") "Друзья ($count)" else "Пользователи ($count)",
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
                                        text = if (searchQuery.isBlank()) {
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

                            if (state.isLoading) {
                                item {
                                    Box(
                                        modifier = Modifier.fillMaxWidth(),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = Color(0xFFFF6B35))
                                    }
                                }
                            }
                        }
                    }

                    // бесконечная прокрутка для пользователей
                    if (selectedTab == "users") {
                        LaunchedEffect(listState, searchQuery) {
                            snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
                                .map { it ?: 0 }
                                .distinctUntilChanged()
                                .collectLatest { lastVisible ->
                                    val total = listState.layoutInfo.totalItemsCount
                                    if (lastVisible >= total - 3 && !state.isLoading) {
                                        viewModel.loadMoreUsers(searchQuery)
                                    }
                                }
                        }
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
        keyboardActions = KeyboardActions(onSearch = { /* закрыть клавиатуру */ })
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
                model = user.profile_photo?.takeIf { it.isNotBlank() },
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