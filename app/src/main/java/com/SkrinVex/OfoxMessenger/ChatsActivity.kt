package com.SkrinVex.OfoxMessenger

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast // <<< ADDED
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.PersonOff
import androidx.compose.material.icons.rounded.Search
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ui.common.MorphLoadingIndicator
import com.SkrinVex.OfoxMessenger.utils.ImageUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File


@OptIn(ExperimentalMaterial3Api::class)
class ChatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vm: ChatsViewModel by viewModels {
            ChatsViewModelFactory(FirebaseAuth.getInstance().currentUser?.uid ?: "")
        }

        setContent {
            Surface(color = Color(0xFF0F0F10)) {
                ChatsScreen(vm = vm, onBack = { finish() })
            }
        }
    }
}

// ---------------------------- ViewModel & модели ----------------------------

data class GroupSummary(
    val id: String = "",
    val name: String = "",
    val photo: String? = null,
    val members: List<String> = emptyList(),
    val createdAt: Long = 0L,
    val ownerId: String = ""
)

data class PersonalChatItem(
    val uid: String,
    val username: String,
    val nickname: String?,
    val profilePhoto: String?,
    val isOnline: Boolean
)

data class UserMini(
    val uid: String,
    val username: String,
    val profilePhoto: String?
)

data class ChatsUiState(
    val selectedTab: String = "personal",
    val loadingGroups: Boolean = false,
    val creating: Boolean = false,
    val groups: List<GroupSummary> = emptyList(),
    val friends: List<PersonalChatItem> = emptyList(),
    val loadingFriends: Boolean = false,
    val users: List<UserMini> = emptyList(),
    val error: String? = null,
    val showSnackbar: Boolean = false
)

class ChatsViewModel(private val currentUid: String) : ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private val db = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()
    private var groupsListener: ValueEventListener? = null
    private var friendsListener: ValueEventListener? = null

    init {
        setupGroupsListener()
        setupFriendsListener()
        preloadUsers()
    }

    private fun setupGroupsListener() {
        if (currentUid.isBlank()) return
        val userGroupsRef = db.getReference("user_groups/$currentUid")
        groupsListener?.let { userGroupsRef.removeEventListener(it) }
        groupsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { loadGroups(snapshot) }
            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(loadingGroups = false, error = error.message, showSnackbar = true)
            }
        }
        userGroupsRef.addValueEventListener(groupsListener!!)
    }

    private fun setupFriendsListener() {
        if (currentUid.isBlank()) return
        val friendsRef = db.getReference("users/$currentUid/friends")
        friendsListener?.let { friendsRef.removeEventListener(it) }
        friendsListener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) { loadFriends(snapshot) }
            override fun onCancelled(error: DatabaseError) {
                _state.value = _state.value.copy(loadingFriends = false, error = error.message)
            }
        }
        friendsRef.addValueEventListener(friendsListener!!)
    }

    private fun preloadUsers(limit: Int = 100) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val snap = db.getReference("users").limitToFirst(limit).get().await()
                val users = mutableListOf<UserMini>()
                for (child in snap.children) {
                    val uid = child.key ?: continue
                    if (uid == currentUid) continue
                    val username = child.child("username").getValue(String::class.java) ?: "@user"
                    val photo = child.child("profile_photo").getValue(String::class.java)
                    users.add(UserMini(uid, username, photo))
                }
                _state.value = _state.value.copy(users = users)
            } catch (_: Exception) { } // Ignore exceptions during preloading
        }
    }

    fun selectTab(tab: String) {
        _state.value = _state.value.copy(selectedTab = tab)
        if (tab == "groups" && _state.value.groups.isEmpty()) {
            loadGroups()
        }
        if (tab == "personal" && _state.value.friends.isEmpty()) {
            db.getReference("users/$currentUid/friends").get().addOnSuccessListener {
                loadFriends(it)
            }
        }
    }

    fun loadGroups(ugSnap: DataSnapshot? = null) {
        if (currentUid.isBlank()) return
        _state.value = _state.value.copy(loadingGroups = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val userGroupsSnapshot = ugSnap ?: db.getReference("user_groups/$currentUid").get().await()
                val groupIds = userGroupsSnapshot.children.mapNotNull { it.key }
                val groups = mutableListOf<GroupSummary>()
                for (gid in groupIds) {
                    val gSnap = db.getReference("group_chats/$gid/meta").get().await()
                    val meta = gSnap.value as? Map<*, *>
                    if (meta != null) {
                        groups.add(
                            GroupSummary(
                                id = gid,
                                name = meta["name"] as? String ?: "Группа",
                                photo = meta["photo"] as? String,
                                members = (meta["members"] as? Map<String, Boolean>)?.keys?.toList() ?: emptyList(),
                                createdAt = (meta["createdAt"] as? Long) ?: 0L,
                                ownerId = meta["ownerId"] as? String ?: ""
                            )
                        )
                    }
                }
                val sortedGroups = groups.sortedByDescending { it.createdAt }
                _state.value = _state.value.copy(loadingGroups = false, groups = sortedGroups)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingGroups = false, error = e.message, showSnackbar = true)
            }
        }
    }

    private fun loadFriends(friendsSnapshot: DataSnapshot) {
        _state.value = _state.value.copy(loadingFriends = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val friendUids = friendsSnapshot.children
                    .filter { it.child("is_friend").getValue(Boolean::class.java) == true }
                    .mapNotNull { it.key }

                val friendsList = mutableListOf<PersonalChatItem>()
                for (friendUid in friendUids) {
                    val userSnap = db.getReference("users/$friendUid").get().await()
                    if (userSnap.exists()) {
                        val userProfile = userSnap.value as Map<String, Any>
                        friendsList.add(
                            PersonalChatItem(
                                uid = friendUid,
                                username = userProfile["username"] as? String ?: "",
                                nickname = userProfile["nickname"] as? String,
                                profilePhoto = userProfile["profile_photo"] as? String,
                                isOnline = userProfile["online"] as? Boolean ?: false
                            )
                        )
                    }
                }
                _state.value = _state.value.copy(loadingFriends = false, friends = friendsList)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingFriends = false, error = e.message)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        groupsListener?.let { db.getReference("user_groups/$currentUid").removeEventListener(it) }
        friendsListener?.let { db.getReference("users/$currentUid/friends").removeEventListener(it) }
    }

    fun createGroup(
        name: String,
        memberUids: List<String>,
        localPhotoUri: Uri?,
        context: Context,
        onCreated: (groupId: String, groupName: String, groupPhoto: String?) -> Unit
    ) {
        if (name.isBlank()) {
            _state.value = _state.value.copy(error = "Введите название группы", showSnackbar = true)
            return
        }
        val members = (memberUids + currentUid).distinct()
        _state.value = _state.value.copy(creating = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val groupId = db.getReference("group_chats").push().key!!
                val photoUrl: String? = if (localPhotoUri != null) {
                    try {
                        val originalFile = uriToFile(context, localPhotoUri)
                        val compressed = com.SkrinVex.OfoxMessenger.utils.ImageUtils.compressImageFile(originalFile, maxDimension = 1024)
                        val ref = storage.reference.child("group_chats/$groupId/photo.jpg")
                        ref.putFile(Uri.fromFile(compressed)).await()
                        val url = ref.downloadUrl.await().toString()
                        compressed.delete(); originalFile.delete()
                        url
                    } catch (e: Exception) { null } // Ignore photo upload errors
                } else null

                val metaRef = db.getReference("group_chats/$groupId/meta")
                val meta = mapOf(
                    "name" to name,
                    "photo" to photoUrl,
                    "ownerId" to currentUid,
                    "createdAt" to System.currentTimeMillis(),
                    "members" to members.associateWith { true }
                )
                metaRef.setValue(meta).await()

                // IMPORTANT: This multi-path update requires permissive Firebase Rules.
                // The rule for "/user_groups/$uid" should be ".write": "auth != null"
                // And the rule for "/user_groups/$uid/$groupId" should also be ".write": "auth != null"
                // If user_groups/$uid/.write is not auth != null, then the following multi-path update will fail
                val updates = hashMapOf<String, Any>()
                for (uid in members) {
                    updates["user_groups/$uid/$groupId"] = true
                }
                db.reference.updateChildren(updates).await()

                db.getReference("group_chats/$groupId/messages").push().setValue(
                    mapOf(
                        "id" to System.currentTimeMillis().toString(),
                        "sender" to "system",
                        "text" to "Группа создана",
                        "timestamp" to System.currentTimeMillis(),
                        "status" to "sent",
                        "type" to "system"
                    )
                ).await()

                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(creating = false)
                    onCreated(groupId, name, photoUrl)
                }

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _state.value = _state.value.copy(creating = false, error = e.message, showSnackbar = true)
                    // If creation fails, we still want to dismiss the dialog
                    onCreated("", "", null) // Pass empty values, assuming onCreated will just dismiss
                }
            }
        }
    }

    fun leaveGroup(groupId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updates = hashMapOf<String, Any?>()
                // IMPORTANT: This multi-path update also requires permissive Firebase Rules.
                // The rules for "/group_chats/$groupId/meta/members/$currentUid"
                // and "/user_groups/$currentUid/$groupId" must allow the current user to write.
                updates["group_chats/$groupId/meta/members/$currentUid"] = null
                updates["user_groups/$currentUid/$groupId"] = null

                db.reference.updateChildren(updates).await()

                withContext(Dispatchers.Main) {
                    onResult(true, "Вы покинули группу")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, "Не удалось покинуть группу: ${e.message}")
                }
            }
        }
    }

    fun deleteGroup(groupId: String, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updates = hashMapOf<String, Any?>()
                updates["group_chats/$groupId"] = null

                val membersSnap = db.getReference("group_chats/$groupId/meta/members").get().await()
                membersSnap.children.forEach { member ->
                    val uid = member.key ?: return@forEach
                    updates["user_groups/$uid/$groupId"] = null
                }

                db.reference.updateChildren(updates).await()
                _state.value = _state.value.copy(groups = _state.value.groups.filter { it.id != groupId })

                withContext(Dispatchers.Main) {
                    onResult(true, "Группа удалена")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Ошибка удаления")
                }
            }
        }
    }

    fun editGroup(
        groupId: String,
        newName: String?,
        newMembers: List<String>?,
        newPhotoUri: Uri?,
        context: android.content.Context,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val updates = hashMapOf<String, Any?>()

                // Обновляем название если изменилось
                if (newName != null && newName.isNotBlank()) {
                    updates["group_chats/$groupId/meta/name"] = newName
                }

                // Загружаем новое фото если выбрано
                var photoUrl: String? = null
                if (newPhotoUri != null) {
                    try {
                        val originalFile = uriToFile(context, newPhotoUri)
                        val compressed = com.SkrinVex.OfoxMessenger.utils.ImageUtils.compressImageFile(
                            originalFile,
                            maxDimension = 1024
                        )
                        val ref = storage.reference.child("group_chats/$groupId/photo.jpg")
                        ref.putFile(Uri.fromFile(compressed)).await()
                        photoUrl = ref.downloadUrl.await().toString()
                        compressed.delete()
                        originalFile.delete()
                        updates["group_chats/$groupId/meta/photo"] = photoUrl
                    } catch (e: Exception) {
                        // Игнорируем ошибки загрузки фото
                    }
                }

                // Обновляем участников если изменились
                if (newMembers != null) {
                    val membersMap = newMembers.associateWith { true }
                    updates["group_chats/$groupId/meta/members"] = membersMap

                    // Получаем старый список участников
                    val oldMembersSnap = db.getReference("group_chats/$groupId/meta/members").get().await()
                    val oldMembers = oldMembersSnap.children.mapNotNull { it.key }

                    // Удаляем индексы для удаленных участников
                    val removed = oldMembers.filter { it !in newMembers }
                    removed.forEach { uid ->
                        updates["user_groups/$uid/$groupId"] = null
                    }

                    // Добавляем индексы для новых участников
                    val added = newMembers.filter { it !in oldMembers }
                    added.forEach { uid ->
                        updates["user_groups/$uid/$groupId"] = true
                    }
                }

                // Применяем все обновления
                if (updates.isNotEmpty()) {
                    db.reference.updateChildren(updates).await()
                }

                // Обновляем локальное состояние
                val updatedGroups = _state.value.groups.map { group ->
                    if (group.id == groupId) {
                        group.copy(
                            name = newName ?: group.name,
                            photo = photoUrl ?: group.photo,
                            members = newMembers ?: group.members
                        )
                    } else group
                }
                _state.value = _state.value.copy(groups = updatedGroups)

                withContext(Dispatchers.Main) {
                    onResult(true, "Группа обновлена")
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    onResult(false, e.message ?: "Ошибка обновления")
                }
            }
        }
    }

    fun uriToFile(context: Context, uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)!!
        val tempFile = File.createTempFile("temp_${System.currentTimeMillis()}", ".jpg", context.cacheDir)
        tempFile.outputStream().use { output ->
            inputStream.copyTo(output)
        }
        return tempFile
    }
}

class ChatsViewModelFactory(private val uid: String) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatsViewModel(uid) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}

// ---------------------------- UI ----------------------------

@Composable
fun ChatsScreen(vm: ChatsViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    val snackHost = remember { SnackbarHostState() }

    LaunchedEffect(state.showSnackbar, state.error) {
        val error = state.error // Fixed smart cast issue
        if (state.showSnackbar && error != null) {
            snackHost.showSnackbar(error)
        }
    }

    Scaffold(
        topBar = { ChatsTopBar(onBack = onBack) },
        floatingActionButton = {
            AnimatedVisibility(visible = state.selectedTab == "groups", enter = fadeIn(), exit = fadeOut()) {
                CreateGroupFab(vm)
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
        containerColor = Color(0xFF0F0F10)
    ) {
        Column(Modifier.fillMaxSize().padding(it)) {
            ModernToggle(
                selectedTab = state.selectedTab,
                onTabSelected = { vm.selectTab(it) },
                hasNotifications = false,
                tab1Text = "Личные",
                tab1Value = "personal",
                tab2Text = "Групповые",
                tab2Value = "groups"
            )
            when (state.selectedTab) {
                "personal" -> PersonalTab(vm = vm)
                "groups" -> GroupsTab(vm)
            }
        }
    }
}

@Composable
private fun ChatsTopBar(onBack: () -> Unit) {
    Surface(modifier = Modifier.fillMaxWidth().height(64.dp), color = Color.Transparent) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(0xFFFF6B35).copy(alpha = 0.1f))
            ) {
                Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = Color(0xFFFF6B35))
            }
            Spacer(Modifier.width(8.dp))
            Text("Чаты", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun PersonalTab(vm: ChatsViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        if (state.loadingFriends && state.friends.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MorphLoadingIndicator()
            }
        } else if (state.friends.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                 Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Rounded.People,
                        contentDescription = null,
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.size(56.dp)
                    )
                    Text("У вас пока нет друзей", color = Color.White.copy(alpha = 0.85f))
                    Text("Найдите друзей в разделе 'Поиск'", color = Color.Gray, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.friends, key = { it.uid }) { friend ->
                    FriendChatCard(friend = friend) {
                        val intent = Intent(context, ChatActivity::class.java).apply {
                            putExtra("friend_uid", friend.uid)
                            putExtra("friend_name", friend.nickname ?: friend.username)
                            putExtra("friend_photo", friend.profilePhoto)
                        }
                        context.startActivity(intent)
                    }
                }
            }
        }
    }
}

@Composable
fun FriendChatCard(friend: PersonalChatItem, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(friend.profilePhoto).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF333333))
                )
                if (friend.isOnline) {
                     Box(
                        modifier = Modifier
                            .size(15.dp)
                            .align(Alignment.BottomEnd)
                            .offset(x = 2.dp, y = 2.dp)
                            .background(Color(0xFF101010), CircleShape)
                            .padding(2.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF00C851), CircleShape)
                        )
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = friend.nickname ?: friend.username,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text("Написать сообщение", color = Color.Gray, fontSize = 13.sp)
            }
        }
    }
}


@Composable
private fun GroupsTab(vm: ChatsViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(state.groups, key = { it.id }) {
                GroupCard(group = it, vm = vm) {
                    val intent = Intent(context, GroupChatActivity::class.java).apply {
                        putExtra("group_id", it.id)
                        putExtra("group_name", it.name)
                        putExtra("group_photo", it.photo)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: GroupSummary, vm: ChatsViewModel, onOpen: () -> Unit) {
    val context = LocalContext.current
    val currentUid = FirebaseAuth.getInstance().currentUser?.uid ?: ""
    val isOwner = group.ownerId == currentUid

    var showMenu by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showLeaveDialog by remember { mutableStateOf(false) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onOpen() },
                    onLongPress = { showMenu = true }
                )
            },
        color = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current).data(group.photo).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    group.name.ifBlank { "Группа" },
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp
                )
                Text("Участников: ${group.members.size}", color = Color.Gray, fontSize = 13.sp)
            }
            AssistChip(
                onClick = onOpen,
                label = { Text("Открыть", color = Color.Black, fontWeight = FontWeight.SemiBold) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFFF6B35))
            )
        }
    }

    if (showMenu) {
        GroupActionMenu(
            group = group,
            isOwner = isOwner,
            onDismiss = { showMenu = false },
            onEdit = {
                showMenu = false
                showEditDialog = true
            },
            onDelete = {
                showMenu = false
                showDeleteDialog = true
            },
            onLeave = {
                showMenu = false
                showLeaveDialog = true
            }
        )
    }

    if (showEditDialog) {
        EditGroupDialog(
            group = group,
            vm = vm,
            onDismiss = { showEditDialog = false }
        )
    }

    // Стилизованный диалог удаления
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Удалить группу?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Это действие нельзя отменить. Группа и все сообщения будут удалены для всех участников.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deleteGroup(group.id) { _, _ -> }
                        showDeleteDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) {
                    Text("Удалить", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showDeleteDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, containerColor = Color(0xFF2A2A2A)),
                    border = null
                ) {
                    Text("Отмена")
                }
            }
        )
    }

    // Стилизованный диалог выхода
    if (showLeaveDialog) {
        AlertDialog(
            onDismissRequest = { showLeaveDialog = false },
            containerColor = Color(0xFF1E1E1E),
            title = { Text("Покинуть группу?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Вы перестанете получать сообщения из этой группы.",
                    color = Color.Gray
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.leaveGroup(group.id) { _, _ -> }
                        showLeaveDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = Color.White)
                ) {
                    Text("Выйти", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showLeaveDialog = false },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White, containerColor = Color(0xFF2A2A2A)),
                    border = null
                ) {
                    Text("Отмена")
                }
            }
        )
    }
}

@Composable
private fun GroupActionMenu(
    group: GroupSummary,
    isOwner: Boolean,
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onLeave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E), // Явно задаем цвет фона
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(group.photo).build(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF333333))
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = group.name.ifBlank { "Группа" },
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isOwner) {
                    // Кнопка Редактировать
                    MenuActionButton(
                        icon = Icons.Rounded.Edit,
                        text = "Редактировать",
                        subText = "Изменить название и фото",
                        color = Color(0xFFFF6B35),
                        onClick = onEdit
                    )

                    Spacer(Modifier.height(8.dp))

                    // Кнопка Удалить
                    MenuActionButton(
                        icon = Icons.Rounded.Delete,
                        text = "Удалить группу",
                        subText = "Безвозвратное удаление",
                        color = Color.Red,
                        onClick = onDelete
                    )
                } else {
                    // Кнопка Покинуть
                    MenuActionButton(
                        icon = Icons.Rounded.ExitToApp,
                        text = "Покинуть группу",
                        subText = "Выйти из чата",
                        color = Color.Red,
                        onClick = onLeave
                    )
                }
            }
        },
        confirmButton = {} // Пустая кнопка, так как мы используем кастомные кнопки в text
    )
}

// Вспомогательный компонент для кнопок меню, чтобы не дублировать код
@Composable
private fun MenuActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    subText: String,
    color: Color,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick),
        color = Color(0xFF2A2A2A)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = color.copy(alpha = 0.15f),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = color,
                    modifier = Modifier.padding(8.dp)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(text, color = color, fontWeight = FontWeight.SemiBold)
                Text(subText, color = Color.Gray, fontSize = 12.sp)
            }
        }
    }
}



@Composable
private fun CreateGroupFab(vm: ChatsViewModel) {
    var show by remember { mutableStateOf(false) }
    FloatingActionButton(
        onClick = { show = true },
        containerColor = Color(0xFFFF6B35),
        contentColor = Color.Black,
        shape = RoundedCornerShape(20.dp)
    ) { Icon(Icons.Rounded.Add, contentDescription = null) }

    if (show) CreateGroupDialog(onDismiss = { show = false }, vm = vm)
}

@Composable
fun CreateGroupDialog(
    vm: ChatsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    // Состояния
    var name by remember { mutableStateOf("") }
    var selectedUsers by remember { mutableStateOf(setOf<String>()) }
    var photoUri by remember { mutableStateOf<Uri?>(null) }
    val search = remember { mutableStateOf("") }

    // Состояние загрузки
    var isCreating by remember { mutableStateOf(false) }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        photoUri = uri
    }

    val filteredFriends = remember(state.friends, search.value) {
        state.friends.filter { it.username.contains(search.value, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = {
            if (!isCreating) onDismiss()
        },
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Создание группы", color = Color.White, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // --- СТИЛЬНАЯ ШАПКА (Фото + Название) ---
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Кнопка фото
                    Box(
                        modifier = Modifier
                            .size(70.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF2B2B2B)) // Фон заглушки чуть светлее
                            .clickable(enabled = !isCreating) { photoLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        if (photoUri != null) {
                            AsyncImage(
                                model = ImageRequest.Builder(context).data(photoUri).build(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            // Иконка, если фото нет
                            Icon(
                                imageVector = Icons.Rounded.AddAPhoto,
                                contentDescription = "Добавить фото",
                                tint = Color.Gray,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    Spacer(Modifier.width(16.dp))

                    // Поле ввода названия
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название группы") },
                        singleLine = true,
                        enabled = !isCreating,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            cursorColor = Color(0xFFFF6B35),
                            focusedBorderColor = Color(0xFFFF6B35),
                            unfocusedBorderColor = Color.Gray,
                            focusedLabelColor = Color(0xFFFF6B35),
                            unfocusedLabelColor = Color.Gray,
                            // Делаем фон поля прозрачным или чуть отличным
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f) // Занимает всё оставшееся место
                    )
                }
                // --- КОНЕЦ ШАПКИ ---

                Spacer(Modifier.height(24.dp))

                // --- Поиск и выбор участников ---
                Text(
                    "Участники",
                    color = Color.Gray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(Modifier.height(8.dp))

                SearchField(value = search.value, onChange = { search.value = it })

                Spacer(Modifier.height(12.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp, max = 300.dp) // Чуть увеличил макс высоту
                        .clip(RoundedCornerShape(16.dp)) // Скругляем углы списка сильнее
                        .background(Color(0xFF161616)) // Фон списка чуть темнее фона диалога
                ) {
                    if (filteredFriends.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Друзья не найдены", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(filteredFriends) { friend ->
                                SelectUserRow(
                                    user = UserMini(
                                        uid = friend.uid,
                                        username = friend.username,
                                        profilePhoto = friend.profilePhoto
                                    ),
                                    checked = friend.uid in selectedUsers,
                                    onToggle = {
                                        if (!isCreating) {
                                            selectedUsers = if (friend.uid in selectedUsers) {
                                                selectedUsers - friend.uid
                                            } else {
                                                selectedUsers + friend.uid
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isNotBlank()) {
                        isCreating = true
                        vm.createGroup(name, selectedUsers.toList(), photoUri, context) { groupId, _, _ ->
                            isCreating = false

                            if (groupId.isNotEmpty()) {
                                onDismiss()
                            } else {
                                val errorMsg = vm.state.value.error ?: "Ошибка создания"
                                Toast.makeText(context, errorMsg, Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                },
                enabled = name.isNotBlank() && !isCreating,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFFF6B35),
                    disabledContainerColor = Color(0xFFFF6B35).copy(alpha = 0.5f)
                ),
                shape = RoundedCornerShape(12.dp) // Скругляем кнопку
            ) {
                if (isCreating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = Color.Black,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text("Создать", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isCreating,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text("Отмена")
            }
        }
    )
}

@Composable
fun EditGroupDialog(
    group: GroupSummary,
    vm: ChatsViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val state by vm.state.collectAsState()

    // Состояние полей
    var name by remember { mutableStateOf(TextFieldValue(group.name)) }
    var newPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    val search = remember { mutableStateOf("") }

    // Управление участниками
    // Инициализируем карту выбранных пользователей (текущие участники = true)
    val selectedMembers = remember {
        mutableStateMapOf<String, Boolean>().apply {
            group.members.forEach { put(it, true) }
        }
    }

    // Собираем общий список пользователей для отображения:
    // Это объединение текущих участников группы и друзей пользователя
    val allRelevantUsers = remember(state.users, state.friends, group.members) {
        val friendsUids = state.friends.map { it.uid }.toSet()
        val existingMemberUids = group.members.toSet()

        // Берем всех из state.users, кто либо друг, либо уже в группе
        state.users.filter { it.uid in friendsUids || it.uid in existingMemberUids }
    }

    val photoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        newPhotoUri = uri
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        properties = DialogProperties(dismissOnClickOutside = false),
        containerColor = Color(0xFF1E1E1E),
        title = { Text("Редактирование", color = Color.White, fontWeight = FontWeight.Bold) },
        confirmButton = {}, // Кнопки управляются внутри layout
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // --- Секция фото и названия ---
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(CircleShape)
                            .clickable { photoLauncher.launch("image/*") },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(newPhotoUri ?: group.photo)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF333333))
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Color.White)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LabeledField(value = name, onValueChange = { name = it }, label = "Название группы")
                    }
                }

                Spacer(Modifier.height(16.dp))

                // --- Секция управления участниками ---
                Text("Участники", color = Color.Gray, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                SearchField(value = search.value, onChange = { search.value = it })
                Spacer(Modifier.height(8.dp))

                val filteredUsers = allRelevantUsers
                    .filter { it.username.contains(search.value, ignoreCase = true) }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp, max = 300.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1B1B1B))
                ) {
                    if (filteredUsers.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                            Text("Пользователи не найдены", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filteredUsers, key = { it.uid }) { user ->
                                // Reuse existing SelectUserRow
                                SelectUserRow(
                                    user = user,
                                    checked = selectedMembers[user.uid] == true
                                ) {
                                    val current = selectedMembers[user.uid] ?: false
                                    selectedMembers[user.uid] = !current
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))

                // --- Кнопки ---
                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { onDismiss() },
                        enabled = !isSaving,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF2A2A2A),
                            contentColor = Color.White
                        ),
                        border = null
                    ) {
                        Text("Отмена")
                    }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (name.text.isNotBlank()) {
                                isSaving = true
                                val finalMembers = selectedMembers.filterValues { it }.keys.toList()
                                vm.editGroup(
                                    groupId = group.id,
                                    newName = name.text,
                                    newMembers = finalMembers,
                                    newPhotoUri = newPhotoUri,
                                    context = context
                                ) { success, _ ->
                                    isSaving = false
                                    if (success) onDismiss()
                                }
                            }
                        },
                        enabled = name.text.isNotBlank() && !isSaving,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B35),
                            contentColor = Color.Black
                        )
                    ) {
                        if (isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.Black,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Сохранить", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun MemberCard(
    user: UserMini?,
    uid: String,
    isOwner: Boolean,
    onRemove: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF1B1B1B)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(user?.profilePhoto)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    user?.username ?: uid,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp
                )
                if (isOwner) {
                    Text(
                        "Владелец",
                        color = Color(0xFFFF6B35),
                        fontSize = 12.sp
                    )
                }
            }

            if (isOwner) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF6B35).copy(alpha = 0.15f)
                ) {
                    Icon(
                        Icons.Rounded.Shield,
                        contentDescription = null,
                        tint = Color(0xFFFF6B35),
                        modifier = Modifier.padding(8.dp).size(18.dp)
                    )
                }
            } else {
                Surface(
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier.clickable { onRemove() }
                ) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = null,
                        tint = Color.Red.copy(alpha = 0.7f),
                        modifier = Modifier.padding(8.dp).size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun AddUserCard(user: UserMini, onAdd: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onAdd() },
        color = Color(0xFF1B1B1B)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(user.profilePhoto)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF333333))
            )
            Spacer(Modifier.width(12.dp))
            Text(
                user.username,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Surface(
                shape = CircleShape,
                color = Color(0xFFFF6B35).copy(alpha = 0.15f)
            ) {
                Icon(
                    Icons.Rounded.Add,
                    contentDescription = null,
                    tint = Color(0xFFFF6B35),
                    modifier = Modifier.padding(8.dp).size(18.dp)
                )
            }
        }
    }
}

@Composable
private fun LabeledField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF2A2A2A)) {
            BasicTextField(value = value, onValueChange = onValueChange, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Поиск", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Surface(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = Color(0xFF2A2A2A)) {
            BasicTextField(value = value, onValueChange = onChange, textStyle = TextStyle(color = Color.White, fontSize = 16.sp), modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun SelectUserRow(user: UserMini, checked: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (checked) Color(0xFFFF6B35).copy(alpha = 0.12f) else Color(0xFF1F1F1F))
            .clickable { onToggle() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current).data(user.profilePhoto).build(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(36.dp).clip(CircleShape).background(Color(0xFF333333))
        )
        Spacer(Modifier.width(10.dp))
        Text(user.username, color = Color.White, modifier = Modifier.weight(1f))
        Surface(
            shape = CircleShape,
            color = if (checked) Color(0xFFFF6B35) else Color.Transparent,
            border = if (!checked) BorderStroke(1.dp, Color.Gray.copy(alpha = 0.4f)) else null
        ) {
            Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                if (checked) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
            }
        }
    }
}
