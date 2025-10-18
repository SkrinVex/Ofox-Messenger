package com.SkrinVex.OfoxMessenger

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
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
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ui.common.MorphLoadingIndicator
import com.SkrinVex.OfoxMessenger.utils.ImageUtils
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.io.File

/**
 * Активити со вкладками "Личные" (заглушка) и "Групповые" (список + создание группы).
 * Черно-оранжевая стилистика в тон ModernToggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
class ChatsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val vm: ChatsViewModel by viewModels {
            ChatsViewModelFactory(FirebaseAuth.getInstance().currentUser?.uid ?: "")
        }

        setContent {
            // При желании оберните в вашу тему OfoxMessengerTheme
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
    val createdAt: Long = 0L
)

data class UserMini(
    val uid: String,
    val username: String,
    val profilePhoto: String?
)

data class ChatsUiState(
    val selectedTab: String = "personal", // personal | groups
    val loadingGroups: Boolean = false,
    val creating: Boolean = false,
    val groups: List<GroupSummary> = emptyList(),
    val users: List<UserMini> = emptyList(),
    val error: String? = null,
    val showSnackbar: Boolean = false
)

class ChatsViewModel(private val currentUid: String) : androidx.lifecycle.ViewModel() {
    private val _state = MutableStateFlow(ChatsUiState())
    val state: StateFlow<ChatsUiState> = _state.asStateFlow()

    private val db = FirebaseDatabase.getInstance()
    private val storage = FirebaseStorage.getInstance()

    init {
        loadGroups()
        preloadUsers()
    }

    fun selectTab(tab: String) {
        _state.value = _state.value.copy(selectedTab = tab)
        if (tab == "groups" && _state.value.groups.isEmpty()) {
            loadGroups()
        }
    }

    fun loadGroups() {
        val uid = currentUid
        if (uid.isBlank()) return
        _state.value = _state.value.copy(loadingGroups = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Предполагаем денормализацию: user_groups/{uid}/{groupId}=true
                val ugSnap = db.getReference("user_groups/$uid").get().await()
                val groupIds = ugSnap.children.mapNotNull { it.key }
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
                                createdAt = (meta["createdAt"] as? Long) ?: 0L
                            )
                        )
                    }
                }
                _state.value = _state.value.copy(loadingGroups = false, groups = groups)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingGroups = false, error = e.message, showSnackbar = true)
            }
        }
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
            } catch (_: Exception) { }
        }
    }

    suspend fun uploadGroupPhotoIfAny(localUri: Uri?, context: android.content.Context): String? {
        if (localUri == null) return null
        return try {
            // Конвертируем Uri во временный файл
            val originalFile = uriToFile(context, localUri)

            // Сжимаем изображение
            val compressed = com.SkrinVex.OfoxMessenger.utils.ImageUtils.compressImageFile(
                originalFile,
                maxDimension = 1024
            )

            // Генерируем уникальный ID для фото
            val gid = db.getReference("group_chats").push().key ?: System.currentTimeMillis().toString()
            val ref = storage.reference.child("group_chats/$gid/photo.jpg")

            // Загружаем в Firebase
            ref.putFile(Uri.fromFile(compressed)).await()
            val url = ref.downloadUrl.await().toString()

            // Очищаем временные файлы
            compressed.delete()
            originalFile.delete()

            url
        } catch (e: Exception) {
            null
        }
    }

    fun createGroup(
        name: String,
        memberUids: List<String>,
        localPhotoUri: Uri?,
        context: android.content.Context,
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
                // Резервируем id заранее, чтобы положить фото по нему
                val groupId = db.getReference("group_chats").push().key!!

                // Загружаем фото (если есть) c сжатием
                val photoUrl: String? = if (localPhotoUri != null) {
                    try {
                        val originalFile = ImageUtils.compressImageFile(
                            uriToFile(context, localPhotoUri)
                        )
                        val compressed = com.SkrinVex.OfoxMessenger.utils.ImageUtils.compressImageFile(originalFile, maxDimension = 1024)
                        val ref = storage.reference.child("group_chats/$groupId/photo.jpg")
                        ref.putFile(Uri.fromFile(compressed)).await()
                        val url = ref.downloadUrl.await().toString()
                        compressed.delete(); originalFile.delete()
                        url
                    } catch (e: Exception) { null }
                } else null

                // Пишем мету группы и участников
                val metaRef = db.getReference("group_chats/$groupId/meta")
                val meta = mapOf(
                    "name" to name,
                    "photo" to photoUrl,
                    "ownerId" to currentUid,
                    "createdAt" to System.currentTimeMillis(),
                    "members" to members.associateWith { true }
                )
                metaRef.setValue(meta).await()

                // Индексы для пользователей
                val updates = hashMapOf<String, Any>()
                for (uid in members) {
                    updates["user_groups/$uid/$groupId"] = true
                }
                db.reference.updateChildren(updates).await()

                // Создаём служебное сообщение приветствия (опционально)
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

                _state.value = _state.value.copy(creating = false)

                onCreated(groupId, name, photoUrl)
            } catch (e: Exception) {
                _state.value = _state.value.copy(creating = false, error = e.message, showSnackbar = true)
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

class ChatsViewModelFactory(private val uid: String) : androidx.lifecycle.ViewModelProvider.Factory {
    override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
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
        if (state.showSnackbar && state.error != null) snackHost.showSnackbar(state.error!!)
    }

    Scaffold(
        topBar = {
            ChatsTopBar(onBack = onBack)
        },
        floatingActionButton = {
            AnimatedVisibility(visible = state.selectedTab == "groups", enter = fadeIn(), exit = fadeOut()) {
                CreateGroupFab(vm)
            }
        },
        snackbarHost = { SnackbarHost(snackHost) },
        containerColor = Color(0xFF0F0F10)
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Ваш ModernToggle
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
                "personal" -> PersonalPlaceholder()
                "groups" -> GroupsTab(vm)
            }
        }

        if (state.creating || state.loadingGroups) {
            // Прогресс-оверлей
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(120.dp).clip(RoundedCornerShape(24.dp)),
                    color = Color(0xFF1E1E1E).copy(alpha = 0.9f)
                ) {
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        MorphLoadingIndicator()
                        Text(
                            text = if (state.creating) "Создание..." else "Загрузка...",
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ChatsTopBar(onBack: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        color = Color.Transparent
    ) {
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
private fun PersonalPlaceholder() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Rounded.Group,
            contentDescription = null,
            tint = Color(0xFFFF6B35),
            modifier = Modifier.size(56.dp)
        )
        Spacer(Modifier.height(12.dp))
        Text("Личные чаты скоро будут доступны", color = Color.White.copy(alpha = 0.85f))
        Text("Следите за обновлениями", color = Color.Gray, fontSize = 13.sp)
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
            items(state.groups, key = { it.id }) { group ->
                GroupCard(group = group) {
                    // Переход в GroupChatActivity
                    val intent = Intent(context, GroupChatActivity::class.java).apply {
                        putExtra("group_id", group.id)
                        putExtra("group_name", group.name)
                        putExtra("group_photo", group.photo)
                    }
                    context.startActivity(intent)
                }
            }
        }
    }
}

@Composable
private fun GroupCard(group: GroupSummary, onOpen: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
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
                modifier = Modifier.size(52.dp).clip(CircleShape).background(Color(0xFF333333))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(group.name.ifBlank { "Группа" }, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                val membersCount = group.members.size
                Text("Участников: $membersCount", color = Color.Gray, fontSize = 13.sp)
            }
            AssistChip(
                onClick = onOpen,
                label = { Text("Открыть", color = Color.Black, fontWeight = FontWeight.SemiBold) },
                colors = AssistChipDefaults.assistChipColors(containerColor = Color(0xFFFF6B35))
            )
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
private fun CreateGroupDialog(onDismiss: () -> Unit, vm: ChatsViewModel) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current
    var name by remember { mutableStateOf(TextFieldValue("")) }
    var pickedImage: Uri? by remember { mutableStateOf(null) }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pickedImage = uri
    }

    val selected = remember { mutableStateMapOf<String, Boolean>() }
    val search = remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {},
        title = {
            Text("Создать группу", color = Color.White, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                // Фото + имя
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape).background(Color(0xFF2A2A2A)).clickable {
                            imagePicker.launch("image/*")
                        }, contentAlignment = Alignment.Center
                    ) {
                        if (pickedImage == null) {
                            Icon(Icons.Rounded.Image, contentDescription = null, tint = Color(0xFFFF6B35))
                        } else {
                            AsyncImage(
                                model = pickedImage,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.matchParentSize().clip(CircleShape)
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        LabeledField(value = name, onValueChange = { name = it }, label = "Название группы")
                        Text("Добавьте участников", color = Color.Gray, fontSize = 12.sp)
                    }
                }

                Spacer(Modifier.height(16.dp))

                SearchField(value = search.value, onChange = { search.value = it })
                Spacer(Modifier.height(8.dp))

                // Список пользователей с мультивыбором
                val filtered = state.users.filter { it.username.contains(search.value, ignoreCase = true) }
                Box(
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).clip(RoundedCornerShape(16.dp)).background(Color(0xFF1B1B1B))
                ) {
                    if (filtered.isEmpty()) {
                        Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                            Text("Никого не найдено", color = Color.Gray)
                        }
                    } else {
                        LazyColumn(contentPadding = PaddingValues(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filtered, key = { it.uid }) { u ->
                                SelectUserRow(user = u, checked = selected[u.uid] == true) {
                                    val cur = selected[u.uid] == true
                                    selected[u.uid] = !cur
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.outlinedButtonColors(
                            containerColor = Color(0xFF2A2A2A),
                            contentColor = Color.White
                        )
                    ) { Icon(Icons.Rounded.Close, null); Spacer(Modifier.width(6.dp)); Text("Отмена") }

                    Spacer(Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val members = selected.filterValues { it }.keys.toList()
                            vm.createGroup(
                                name = name.text.trim(),
                                memberUids = members,
                                localPhotoUri = pickedImage,
                                context = context,
                            ) { gid, gname, gphoto ->
                                onDismiss()
                                // Навигация в группу
                                val intent = Intent(context, GroupChatActivity::class.java).apply {
                                    putExtra("group_id", gid)
                                    putExtra("group_name", gname)
                                    putExtra("group_photo", gphoto)
                                }
                                context.startActivity(intent)
                            }
                        },
                        enabled = name.text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35), contentColor = Color.Black)
                    ) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(6.dp)); Text("Создать", fontWeight = FontWeight.Bold) }
                }
            }
        },
        containerColor = Color(0xFF1E1E1E)
    )
}

@Composable
private fun LabeledField(value: TextFieldValue, onValueChange: (TextFieldValue) -> Unit, label: String) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF2A2A2A)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text("Поиск", color = Color.Gray, fontSize = 12.sp)
        Spacer(Modifier.height(6.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color(0xFF2A2A2A)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
            )
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