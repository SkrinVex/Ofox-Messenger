package com.SkrinVex.OfoxMessenger

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.SkrinVex.OfoxMessenger.network.ApiService
import com.SkrinVex.OfoxMessenger.utils.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody
import java.io.File
import java.io.Serializable
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.tasks.await
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
import kotlinx.coroutines.async

fun String.toRequestBodyOrNull(): RequestBody? =
    if (this.isNotBlank()) RequestBody.create("text/plain".toMediaTypeOrNull(), this) else null

fun File.toMultipartBodyPart(fieldName: String): MultipartBody.Part =
    MultipartBody.Part.createFormData(
        fieldName,
        name,
        RequestBody.create("image/*".toMediaTypeOrNull(), this)
    )

data class ProfileEditState(
    val isLoading: Boolean = false,
    val isSaving: Boolean = false,
    val profile: ProfileCheckResponse? = null,
    val fields: Map<String, String> = emptyMap(),
    val message: String? = null,
    val showSnackbar: Boolean = false,
    val isNewUser: Boolean = false
)

data class ProfileCheckResponse(
    val success: Boolean = true,
    val username: String? = null,
    val nickname: String? = null,
    val email: String? = null,
    val birthday: String? = null,
    val status: String? = null,
    val bio: String? = null,
    val profile_photo: String? = null,
    val background_photo: String? = null,
    val profile_completion: Int = 0,
    val error: String? = null
) : Serializable

class ProfileEditViewModel(private val uid: String) : ViewModel() {
    private val _state = MutableStateFlow(ProfileEditState())
    val state: StateFlow<ProfileEditState> = _state.asStateFlow()
    private val apiService = ApiService.create()

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null)
            try {
                val snapshot = withContext(Dispatchers.IO) {
                    FirebaseDatabase.getInstance()
                        .getReference("users/$uid")
                        .get()
                        .await()
                }
                val profileData = snapshot.value as? Map<String, Any>
                val profile = if (profileData != null) {
                    ProfileCheckResponse(
                        success = true,
                        username = profileData["username"] as? String,
                        nickname = profileData["nickname"] as? String,
                        email = profileData["email"] as? String ?: FirebaseAuth.getInstance().currentUser?.email,
                        birthday = profileData["birthday"] as? String,
                        status = profileData["status"] as? String,
                        bio = profileData["bio"] as? String,
                        profile_photo = profileData["profile_photo"] as? String,
                        background_photo = profileData["background_photo"] as? String,
                        profile_completion = calculateProfileCompletion(profileData)
                    )
                } else {
                    ProfileCheckResponse(
                        success = true,
                        email = FirebaseAuth.getInstance().currentUser?.email,
                        profile_completion = 0
                    )
                }
                _state.value = _state.value.copy(
                    isLoading = false,
                    profile = profile,
                    fields = buildFieldsMap(profile),
                    isNewUser = profile.username.isNullOrBlank()
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "Ошибка загрузки профиля: ${e.message}",
                    showSnackbar = true
                )
            }
        }
    }

    fun onFieldChange(field: String, value: String) {
        val updatedValue = if (field == "username" && !value.startsWith("@")) {
            "@$value"
        } else {
            value
        }
        _state.value = _state.value.copy(
            fields = _state.value.fields.toMutableMap().apply { put(field, updatedValue) }
        )
    }

    /**
     * Основная функция сохранения.
     * Автоматически исправляет "битые" ссылки (token=4), даже если пользователь не менял фото.
     */
    fun saveProfileWithPhoto(
        username: String?,
        nickname: String?,
        email: String?,
        birthday: String?,
        status: String?,
        bio: String?,
        profilePhotoFile: File?,
        backgroundPhotoFile: File?,
        onSuccess: (ProfileCheckResponse?) -> Unit
    ) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true, message = null)
            try {
                val profileRef = FirebaseDatabase.getInstance().getReference("users/$uid")
                val oldProfilePhotoUrl = _state.value.profile?.profile_photo
                val oldBackgroundPhotoUrl = _state.value.profile?.background_photo

                // === 1. Обработка ФОТО (Параллельно) ===

                // Обработка аватара
                val profilePhotoUrlDeferred = viewModelScope.async { // Добавлено viewModelScope.
                    if (profilePhotoFile != null) {
                        uploadAndGetUrl(profilePhotoFile, "profile_photo.jpg", 1024)
                    } else if (isLinkBroken(oldProfilePhotoUrl)) {
                        getValidUrlOrNull("profile_photo.jpg")
                    } else {
                        null
                    }
                }

                // Обработка фона
                val backgroundPhotoUrlDeferred = viewModelScope.async { // Добавлено viewModelScope.
                    if (backgroundPhotoFile != null) {
                        uploadAndGetUrl(backgroundPhotoFile, "background_photo.jpg", 1920)
                    } else if (isLinkBroken(oldBackgroundPhotoUrl)) {
                        getValidUrlOrNull("background_photo.jpg")
                    } else {
                        null
                    }
                }

                val newProfilePhotoUrl = profilePhotoUrlDeferred.await()
                val newBackgroundPhotoUrl = backgroundPhotoUrlDeferred.await()

                // === 2. Формируем данные для обновления ===
                val updateData = mutableMapOf<String, Any?>()

                username?.takeIf { it.isNotBlank() && it != "@" && it != _state.value.profile?.username }?.let {
                    updateData["username"] = it
                }
                nickname?.takeIf { it != _state.value.profile?.nickname }?.let {
                    updateData["nickname"] = it
                }
                email?.takeIf { it.isNotBlank() && it != _state.value.profile?.email }?.let {
                    updateData["email"] = it
                }
                birthday?.takeIf { it != _state.value.profile?.birthday }?.let {
                    updateData["birthday"] = it
                }
                status?.takeIf { it != _state.value.profile?.status }?.let {
                    updateData["status"] = it
                }
                bio?.takeIf { it != _state.value.profile?.bio }?.let {
                    updateData["bio"] = it
                }

                // Добавляем ссылки только если они изменились (были загружены или исправлены)
                newProfilePhotoUrl?.let { updateData["profile_photo"] = it }
                newBackgroundPhotoUrl?.let { updateData["background_photo"] = it }

                if (updateData.isNotEmpty()) {
                    // === 3. Обновляем профиль в БД ===
                    profileRef.updateChildren(updateData).await()

                    // === 4. УДАЛЕНИЕ СТАРЫХ ФАЙЛОВ УБРАНО ===
                    // При использовании имен "profile_photo.jpg" Firebase сам заменяет старый файл новым.
                    // Вызов .delete() здесь приводил к 404, так как удалял только что загруженный файл.

                    // === 5. Пересчёт процента заполненности ===
                    val currentSnapshot = profileRef.get().await()
                    val currentData = currentSnapshot.value as? Map<String, Any> ?: emptyMap()
                    val newCompletion = calculateProfileCompletion(currentData)
                    profileRef.child("profile_completion").setValue(newCompletion).await()
                }

                // === 6. Получаем итоговый результат для UI ===
                val updatedSnapshot = profileRef.get().await()
                val updatedProfileData = updatedSnapshot.value as? Map<String, Any>

                val updatedProfile = if (updatedProfileData != null) {
                    ProfileCheckResponse(
                        success = true,
                        username = updatedProfileData["username"] as? String,
                        nickname = updatedProfileData["nickname"] as? String,
                        email = updatedProfileData["email"] as? String ?: FirebaseAuth.getInstance().currentUser?.email,
                        birthday = updatedProfileData["birthday"] as? String,
                        status = updatedProfileData["status"] as? String,
                        bio = updatedProfileData["bio"] as? String,
                        profile_photo = updatedProfileData["profile_photo"] as? String,
                        background_photo = updatedProfileData["background_photo"] as? String,
                        profile_completion = (updatedProfileData["profile_completion"] as? Number)?.toInt()
                            ?: 0 // Используем Number для безопасности
                    )
                } else {
                    ProfileCheckResponse(
                        success = true,
                        email = FirebaseAuth.getInstance().currentUser?.email,
                        profile_completion = 0
                    )
                }

                _state.value = _state.value.copy(
                    isSaving = false,
                    message = "Сохранено!",
                    showSnackbar = true,
                    profile = updatedProfile,
                    fields = buildFieldsMap(updatedProfile) // Убедитесь, что эта функция у вас есть
                )
                onSuccess(updatedProfile)

            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isSaving = false,
                    message = "Ошибка сохранения: ${e.message}",
                    showSnackbar = true
                )
            }
        }
    }

    // ==========================================
    // ВСПОМОГАТЕЛЬНЫЕ ФУНКЦИИ (добавьте их в класс)
    // ==========================================

    /**
     * Загружает файл, удаляет сжатую копию и возвращает URL.
     */
    private suspend fun uploadAndGetUrl(file: File, path: String, maxDim: Int): String {
        val compressedFile = ImageUtils.compressImageFile(file, maxDimension = maxDim)
        val storageRef = FirebaseStorage.getInstance().reference.child("users/$uid/$path")

        storageRef.putFile(Uri.fromFile(compressedFile)).await()
        val url = storageRef.downloadUrl.await().toString()

        compressedFile.delete() // Чистим кэш
        return url
    }

    /**
     * Пытается получить валидную ссылку на существующий файл.
     * Используется для "лечения" битых ссылок.
     */
    private suspend fun getValidUrlOrNull(path: String): String? {
        return try {
            val storageRef = FirebaseStorage.getInstance().reference.child("users/$uid/$path")
            storageRef.downloadUrl.await().toString()
        } catch (e: Exception) {
            null // Файла нет или ошибка — оставляем как есть
        }
    }

    /**
     * Проверяет, является ли ссылка "битой" (ошибка token=4).
     */
    private fun isLinkBroken(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        // Главный признак проблемы
        if (url.contains("token=4")) return true
        // Дополнительная проверка: ссылка должна вести на firebase storage
        if (!url.contains("firebasestorage.googleapis.com")) return false
        return false
    }

    fun dismissSnackbar() {
        _state.value = _state.value.copy(showSnackbar = false)
    }

    private fun buildFieldsMap(profile: ProfileCheckResponse): MutableMap<String, String> {
        val map = mutableMapOf<String, String>()
        map["username"] = profile.username ?: "@"
        map["nickname"] = profile.nickname ?: ""
        map["email"] = profile.email ?: ""
        map["birthday"] = profile.birthday ?: ""
        map["status"] = profile.status ?: ""
        map["bio"] = profile.bio ?: ""
        map["profile_photo"] = profile.profile_photo ?: ""
        map["background_photo"] = profile.background_photo ?: ""
        return map
    }

    private fun calculateProfileCompletion(profileData: Map<String, Any?>): Int {
        val fields =
            listOf("username", "nickname", "email", "birthday", "status", "bio", "profile_photo", "background_photo")
        val filledFields = fields.count { field ->
            profileData[field]?.toString()?.isNotBlank() == true
        }
        return (filledFields * 100) / fields.size
    }

    fun isContinueButtonEnabled(username: String?, profilePhotoFile: File?, backgroundPhotoFile: File?): Boolean {
        return (username?.isNotBlank() == true && username != "@") || profilePhotoFile != null || backgroundPhotoFile != null
    }
}
