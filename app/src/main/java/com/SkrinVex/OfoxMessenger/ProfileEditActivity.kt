package com.SkrinVex.OfoxMessenger

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.SkrinVex.OfoxMessenger.ui.common.enableInternetCheck
import com.SkrinVex.OfoxMessenger.ui.theme.OfoxMessengerTheme
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.SkrinVex.OfoxMessenger.ProfileEditActivity.Companion.RESULT_PROFILE_UPDATED
import java.io.File
import java.io.Serializable

class ProfileEditActivity : ComponentActivity() {
    companion object {
        const val RESULT_PROFILE_UPDATED = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableInternetCheck()
        val uid = intent.getStringExtra("uid") ?: ""
        setContent {
            OfoxMessengerTheme {
                ProfileEditScreen(uid = uid)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(uid: String) {
    val viewModel = remember { ProfileEditViewModel(uid) }
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()

    var profilePhotoUri by remember { mutableStateOf<Uri?>(null) }
    var backgroundPhotoUri by remember { mutableStateOf<Uri?>(null) }
    var profilePhotoFile by remember { mutableStateOf<File?>(null) }
    var backgroundPhotoFile by remember { mutableStateOf<File?>(null) }
    var showPhotoMenu by remember { mutableStateOf(false) }
    var photoMenuType by remember { mutableStateOf("") } // "profile" or "background"

    // Photo launchers
    val profilePhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        profilePhotoUri = uri
        profilePhotoFile = uri?.let { getFileFromUri(activity, it) }
        android.util.Log.d("ProfileEditScreen", "Profile photo selected: $uri")
    }

    val backgroundPhotoLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        backgroundPhotoUri = uri
        backgroundPhotoFile = uri?.let { getFileFromUri(activity, it) }
        android.util.Log.d("ProfileEditScreen", "Background photo selected: $uri")
    }

    val scrollState = rememberScrollState()

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF101010))) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = if (state.isNewUser) "Настройка аккаунта" else "Редактирование профиля",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 16.dp)
            )

            // Profile completion progress (только для существующих пользователей)
            if (!state.isNewUser) {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Заполненность профиля",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = (state.profile?.profile_completion ?: 0) / 100f,
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = Color(0xFFFF6B35),
                            trackColor = Color(0xFF333333)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${state.profile?.profile_completion ?: 0}%",
                            color = Color(0xFFFF6B35),
                            fontSize = 12.sp
                        )
                    }
                }
            }

            // Photo selection section
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Фотографии",
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Profile photo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF333333))
                                    .clickable {
                                        showPhotoMenu = true
                                        photoMenuType = "profile"
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val profileImageModel = when {
                                    profilePhotoUri != null -> {
                                        android.util.Log.d("ProfileEditScreen", "Using local profile photo URI: $profilePhotoUri")
                                        profilePhotoUri.toString()
                                    }
                                    state.profile?.profile_photo?.isNotBlank() == true -> {
                                        val url = "https://api.skrinvex.su${state.profile?.profile_photo}"
                                        android.util.Log.d("ProfileEditScreen", "Using server profile photo URL: $url")
                                        url
                                    }
                                    else -> {
                                        android.util.Log.d("ProfileEditScreen", "No profile photo available")
                                        null
                                    }
                                }

                                if (profileImageModel != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(profileImageModel)
                                            .fallback(android.R.drawable.ic_menu_gallery)
                                            .error(android.R.drawable.ic_menu_gallery)
                                            .listener(
                                                onSuccess = { _, _ -> android.util.Log.d("ProfileEditScreen", "Profile image loaded: $profileImageModel") },
                                                onError = { _, throwable -> android.util.Log.e("ProfileEditScreen", "Profile image error: ${throwable.throwable?.message}") }
                                            )
                                            .build(),
                                        contentDescription = "Фото профиля",
                                        modifier = Modifier
                                            .size(100.dp)
                                            .clip(CircleShape),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Person,
                                        contentDescription = "Фото профиля",
                                        tint = Color(0xFFFF6B35),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Фото профиля",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }

                        // Background photo
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(width = 160.dp, height = 100.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Color(0xFF333333))
                                    .clickable {
                                        showPhotoMenu = true
                                        photoMenuType = "background"
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                val backgroundImageModel = when {
                                    backgroundPhotoUri != null -> {
                                        android.util.Log.d("ProfileEditScreen", "Using local background photo URI: $backgroundPhotoUri")
                                        backgroundPhotoUri.toString()
                                    }
                                    state.profile?.background_photo?.isNotBlank() == true -> {
                                        val url = "https://api.skrinvex.su${state.profile?.background_photo}"
                                        android.util.Log.d("ProfileEditScreen", "Using server background photo URL: $url")
                                        url
                                    }
                                    else -> {
                                        android.util.Log.d("ProfileEditScreen", "No background photo available")
                                        null
                                    }
                                }

                                if (backgroundImageModel != null) {
                                    AsyncImage(
                                        model = ImageRequest.Builder(LocalContext.current)
                                            .data(backgroundImageModel)
                                            .fallback(android.R.drawable.ic_menu_gallery)
                                            .error(android.R.drawable.ic_menu_gallery)
                                            .listener(
                                                onSuccess = { _, _ -> android.util.Log.d("ProfileEditScreen", "Background image loaded: $backgroundImageModel") },
                                                onError = { _, throwable -> android.util.Log.e("ProfileEditScreen", "Background image error: ${throwable.throwable?.message}") }
                                            )
                                            .build(),
                                        contentDescription = "Фон профиля",
                                        modifier = Modifier
                                            .size(width = 160.dp, height = 100.dp)
                                            .clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                } else {
                                    Icon(
                                        Icons.Filled.Image,
                                        contentDescription = "Фон профиля",
                                        tint = Color(0xFFFF6B35),
                                        modifier = Modifier.size(48.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "Фон профиля",
                                color = Color.White,
                                fontSize = 14.sp
                            )
                        }
                    }
                }
            }

            // Form fields
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    // Для новых пользователей показываем только username
                    if (state.isNewUser) {
                        OutlinedTextField(
                            value = state.fields["username"] ?: "@",
                            onValueChange = {
                                val cleanValue = if (it.startsWith("@")) it.drop(1) else it
                                viewModel.onFieldChange("username", cleanValue)
                            },
                            label = { Text("Имя пользователя", color = Color.Gray) },
                            leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFFFF6B35)) },
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF6B35),
                                unfocusedBorderColor = Color(0xFF444444),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                cursorColor = Color(0xFFFF6B35),
                                focusedContainerColor = Color.Transparent,
                                unfocusedContainerColor = Color.Transparent
                            ),
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Text
                            )
                        )
                    } else {
                        // Для существующих пользователей показываем все поля
                        val fieldsToShow = listOf(
                            ProfileField("username", "Имя пользователя", Icons.Filled.Person, true),
                            ProfileField("nickname", "Никнейм", Icons.Filled.Person, true),
                            ProfileField("email", "Email", Icons.Filled.Email, false),
                            ProfileField("birthday", "Дата рождения", Icons.Filled.DateRange, true),
                            ProfileField("status", "Статус", Icons.Filled.Info, true),
                            ProfileField("bio", "О себе", Icons.Filled.Info, true)
                        )

                        // Логирование для отладки
                        android.util.Log.d("ProfileEditScreen", "Fields to show: ${fieldsToShow.map { it.field }}")
                        android.util.Log.d("ProfileEditScreen", "State fields: ${state.fields}")

                        fieldsToShow.forEach { field ->
                            Spacer(modifier = Modifier.height(8.dp))
                            when (field.field) {
                                "username" -> {
                                    OutlinedTextField(
                                        value = state.fields["username"] ?: "@",
                                        onValueChange = {
                                            val cleanValue = if (it.startsWith("@")) it.drop(1) else it
                                            viewModel.onFieldChange("username", cleanValue)
                                        },
                                        label = { Text("Имя пользователя", color = Color.Gray) },
                                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFFFF6B35)) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFF6B35),
                                            unfocusedBorderColor = Color(0xFF444444),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFFFF6B35),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Next,
                                            keyboardType = KeyboardType.Text
                                        )
                                    )
                                }
                                "nickname" -> {
                                    OutlinedTextField(
                                        value = state.fields["nickname"] ?: "",
                                        onValueChange = { viewModel.onFieldChange("nickname", it) },
                                        label = { Text("Никнейм", color = Color.Gray) },
                                        leadingIcon = { Icon(Icons.Filled.Person, contentDescription = null, tint = Color(0xFFFF6B35)) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFF6B35),
                                            unfocusedBorderColor = Color(0xFF444444),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFFFF6B35),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Next,
                                            keyboardType = KeyboardType.Text
                                        )
                                    )
                                }
                                "email" -> {
                                    OutlinedTextField(
                                        value = state.fields["email"] ?: "",
                                        onValueChange = {}, // Запрещаем редактирование
                                        label = { Text("Email", color = Color.Gray) },
                                        leadingIcon = { Icon(Icons.Filled.Email, contentDescription = null, tint = Color(0xFFFF6B35)) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        enabled = false,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            disabledBorderColor = Color(0xFF444444),
                                            disabledTextColor = Color.Gray,
                                            disabledLabelColor = Color.Gray,
                                            disabledLeadingIconColor = Color(0xFFFF6B35),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Next,
                                            keyboardType = KeyboardType.Email
                                        )
                                    )
                                }
                                "birthday" -> {
                                    DatePickerField(
                                        value = state.fields["birthday"] ?: "",
                                        onValueChange = { viewModel.onFieldChange("birthday", it) },
                                        label = "Дата рождения"
                                    )
                                }
                                "status" -> {
                                    OutlinedTextField(
                                        value = state.fields["status"] ?: "",
                                        onValueChange = { viewModel.onFieldChange("status", it) },
                                        label = { Text("Статус", color = Color.Gray) },
                                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFF6B35)) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFF6B35),
                                            unfocusedBorderColor = Color(0xFF444444),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFFFF6B35),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Next,
                                            keyboardType = KeyboardType.Text
                                        )
                                    )
                                }
                                "bio" -> {
                                    OutlinedTextField(
                                        value = state.fields["bio"] ?: "",
                                        onValueChange = { viewModel.onFieldChange("bio", it) },
                                        label = { Text("О себе", color = Color.Gray) },
                                        leadingIcon = { Icon(Icons.Filled.Info, contentDescription = null, tint = Color(0xFFFF6B35)) },
                                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                        maxLines = 3,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = Color(0xFFFF6B35),
                                            unfocusedBorderColor = Color(0xFF444444),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White,
                                            cursorColor = Color(0xFFFF6B35),
                                            focusedContainerColor = Color.Transparent,
                                            unfocusedContainerColor = Color.Transparent
                                        ),
                                        keyboardOptions = KeyboardOptions(
                                            imeAction = ImeAction.Done,
                                            keyboardType = KeyboardType.Text
                                        )
                                    )
                                }
                                else -> {
                                    android.util.Log.w("ProfileEditScreen", "Unknown field: ${field.field}")
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Save/Continue button
            if (state.isNewUser) {
                if (viewModel.isContinueButtonEnabled(state.fields["username"], profilePhotoFile, backgroundPhotoFile)) {
                    Button(
                        onClick = {
                            viewModel.saveProfileWithPhoto(
                                username = state.fields["username"],
                                nickname = null,
                                email = null,
                                birthday = null,
                                status = null,
                                bio = null,
                                profilePhotoFile = profilePhotoFile,
                                backgroundPhotoFile = backgroundPhotoFile
                            ) { updatedProfile ->
                                // Для новых пользователей перенаправляем в MainPageActivity
                                val intent = Intent(context, MainPageActivity::class.java)
                                intent.putExtra("uid", uid)
                                context.startActivity(intent)
                                activity.finish()
                            }
                        },
                        enabled = !state.isSaving,
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                        shape = RoundedCornerShape(16.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFFFF6B35),
                            disabledContainerColor = Color.Gray
                        )
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = "Продолжить",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            } else {
                Button(
                    onClick = {
                        viewModel.saveProfileWithPhoto(
                            username = state.fields["username"],
                            nickname = state.fields["nickname"],
                            email = state.fields["email"],
                            birthday = state.fields["birthday"],
                            status = state.fields["status"],
                            bio = state.fields["bio"],
                            profilePhotoFile = profilePhotoFile,
                            backgroundPhotoFile = backgroundPhotoFile
                        ) { updatedProfile ->
                            // Возвращаем результат в ProfileViewActivity
                            val resultIntent = Intent()
                            resultIntent.putExtra("updated_profile", updatedProfile as Serializable?)
                            activity.setResult(RESULT_PROFILE_UPDATED, resultIntent)
                            activity.finish()
                        }
                    },
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF6B35),
                        disabledContainerColor = Color.Gray
                    )
                ) {
                    if (state.isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = "Сохранить",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))
        }

        // Photo selection bottom sheet
        if (showPhotoMenu) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .clickable { showPhotoMenu = false },
                contentAlignment = Alignment.BottomCenter
            ) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .clickable { },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Выберите источник",
                            color = Color.White,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    showPhotoMenu = false
                                    if (photoMenuType == "profile") {
                                        profilePhotoLauncher.launch("image/*")
                                    } else {
                                        backgroundPhotoLauncher.launch("image/*")
                                    }
                                }
                            ) {
                                Icon(
                                    Icons.Filled.PhotoLibrary,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B35),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "Галерея",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }

                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    showPhotoMenu = false
                                    // TODO: Implement camera capture
                                }
                            ) {
                                Icon(
                                    Icons.Filled.PhotoCamera,
                                    contentDescription = null,
                                    tint = Color(0xFFFF6B35),
                                    modifier = Modifier.size(32.dp)
                                )
                                Text(
                                    text = "Камера",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { showPhotoMenu = false },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF333333)
                            )
                        ) {
                            Text("Отмена", color = Color.White)
                        }
                    }
                }
            }
        }

        // Snackbar
        if (state.showSnackbar && state.message != null) {
            Card(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (state.message?.contains("Сохранено") == true) {
                        Color(0xFF4CAF50)
                    } else {
                        Color(0xFFF44336)
                    }
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = state.message ?: "",
                        color = Color.White,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        onClick = { viewModel.dismissSnackbar() }
                    ) {
                        Text("ОК", color = Color.White)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String
) {
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState()

    OutlinedTextField(
        value = value,
        onValueChange = { },
        label = { Text(label, color = Color.Gray) },
        leadingIcon = {
            Icon(
                Icons.Filled.DateRange,
                contentDescription = null,
                tint = Color(0xFFFF6B35)
            )
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { showDatePicker = true },
        readOnly = true,
        enabled = false,
        colors = OutlinedTextFieldDefaults.colors(
            disabledBorderColor = Color(0xFF444444),
            disabledTextColor = Color.White,
            disabledLabelColor = Color.Gray,
            disabledLeadingIconColor = Color(0xFFFF6B35),
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent
        ),
        keyboardOptions = KeyboardOptions(
            imeAction = ImeAction.Next,
            keyboardType = KeyboardType.Text
        )
    )

    if (showDatePicker) {
        DatePickerDialog(
            onDateSelected = { selectedDate ->
                onValueChange(selectedDate)
                showDatePicker = false
            },
            onDismiss = { showDatePicker = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerDialog(
    onDateSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val datePickerState = rememberDatePickerState()

    DatePickerDialog(
        onDismissRequest = { onDismiss() },
        confirmButton = {
            TextButton(onClick = {
                datePickerState.selectedDateMillis?.let { millis ->
                    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
                    val date = java.util.Date(millis)
                    onDateSelected(formatter.format(date))
                }
                onDismiss()
            }) {
                Text("OK", color = Color(0xFFFF6B35))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDismiss() }) {
                Text("Отмена", color = Color.Gray)
            }
        },
        colors = DatePickerDefaults.colors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        DatePicker(
            state = datePickerState,
            colors = DatePickerDefaults.colors(
                containerColor = Color(0xFF1E1E1E),
                titleContentColor = Color.White,
                headlineContentColor = Color.White,
                weekdayContentColor = Color.White,
                subheadContentColor = Color.White,
                dayContentColor = Color.White,
                selectedDayContentColor = Color.White,
                selectedDayContainerColor = Color(0xFFFF6B35),
                todayContentColor = Color(0xFFFF6B35),
                todayDateBorderColor = Color(0xFFFF6B35)
            )
        )
    }
}

data class ProfileField(
    val field: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val editable: Boolean
)

fun getFileFromUri(activity: Activity, uri: Uri): File? {
    return try {
        val inputStream = activity.contentResolver.openInputStream(uri) ?: return null
        val extension = when (activity.contentResolver.getType(uri)) {
            "image/jpeg" -> ".jpg"
            "image/png" -> ".png"
            "image/webp" -> ".webp"
            else -> ".jpg"
        }
        val file = File(activity.cacheDir, "temp_${System.currentTimeMillis()}$extension")
        file.outputStream().use { inputStream.copyTo(it) }
        file
    } catch (e: Exception) {
        null
    }
}