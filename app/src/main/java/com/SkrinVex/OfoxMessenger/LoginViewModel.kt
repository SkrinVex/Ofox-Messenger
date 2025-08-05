package com.SkrinVex.OfoxMessenger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class LoginState(
    val email: String = "",
    val password: String = "",
    val isLoading: Boolean = false,
    val message: String? = null,
    val isSuccess: Boolean = false,
    val isEmailNotVerified: Boolean = false,
    val isPasswordVisible: Boolean = false,
    val isAccountBlocked: Boolean = false
)

sealed class LoginEvent {
    data class EmailChanged(val email: String) : LoginEvent()
    data class PasswordChanged(val password: String) : LoginEvent()
    object LoginClicked : LoginEvent()
    object MessageShown : LoginEvent()
    object TogglePasswordVisibility : LoginEvent()
}

class LoginViewModel : ViewModel() {
    private val _state = MutableStateFlow(LoginState())
    val state: StateFlow<LoginState> = _state.asStateFlow()

    var onLoginSuccess: ((userId: String) -> Unit)? = null

    fun onEvent(event: LoginEvent) {
        when (event) {
            is LoginEvent.EmailChanged -> {
                _state.value = _state.value.copy(email = event.email)
            }
            is LoginEvent.PasswordChanged -> {
                _state.value = _state.value.copy(password = event.password)
            }
            is LoginEvent.LoginClicked -> {
                performLogin()
            }
            is LoginEvent.MessageShown -> {
                _state.value = _state.value.copy(message = null)
            }
            is LoginEvent.TogglePasswordVisibility -> {
                _state.value = _state.value.copy(
                    isPasswordVisible = !_state.value.isPasswordVisible
                )
            }
        }
    }

    private fun performLogin() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, message = null, isAccountBlocked = false)

            val email = _state.value.email.trim()
            val password = _state.value.password

            if (email.isEmpty() || password.isEmpty()) {
                _state.value = _state.value.copy(
                    isLoading = false,
                    message = "Пожалуйста, заполните все поля"
                )
                return@launch
            }

            try {
                val auth = FirebaseAuth.getInstance()
                val result = auth.signInWithEmailAndPassword(email, password).await()
                val user = result.user

                if (user != null) {
                    if (user.isEmailVerified) {
                        // Проверяем доступ к базе данных
                        // Если пользователь заблокирован, любая попытка чтения вернет Permission denied
                        try {
                            // Делаем тестовый запрос к базе данных
                            val testSnapshot = FirebaseDatabase.getInstance()
                                .getReference("users/${user.uid}/profile")
                                .get()
                                .await()

                            // Если мы дошли сюда, значит доступ к базе есть
                            // Теперь проверяем данные пользователя
                            val profileSnapshot = FirebaseDatabase.getInstance()
                                .getReference("users/${user.uid}")
                                .get()
                                .await()

                            val profile = profileSnapshot.value as? Map<String, Any>

                            // Проверяем наличие email в профиле
                            val profileEmail = profile?.get("email") as? String
                            if (profileEmail.isNullOrEmpty()) {
                                // Если нет email в профиле, это подозрительный аккаунт
                                auth.signOut()
                                _state.value = _state.value.copy(
                                    isLoading = false,
                                    message = "Ваш аккаунт некорректен. Обратитесь в службу поддержки."
                                )
                                return@launch
                            }

                            // Если все проверки пройдены, успешный вход
                            onLoginSuccess?.invoke(user.uid)
                            _state.value = _state.value.copy(
                                isLoading = false,
                                isSuccess = true,
                                message = null
                            )

                        } catch (dbException: Exception) {
                            // Обрабатываем ошибки доступа к базе данных
                            auth.signOut()

                            val errorMessage = when {
                                // Ошибки доступа - скорее всего пользователь заблокирован
                                dbException.message?.contains("Permission denied", true) == true ||
                                        dbException.message?.contains("Database access denied", true) == true ||
                                        dbException.message?.contains("Access denied", true) == true -> {
                                    _state.value = _state.value.copy(isAccountBlocked = true)
                                    "Ваш аккаунт был заблокирован администратором. Если считаете это ошибкой, обратитесь в службу поддержки."
                                }
                                // Другие ошибки базы данных
                                else -> {
                                    "Ошибка подключения к серверу. Проверьте интернет-соединение и попробуйте снова."
                                }
                            }

                            _state.value = _state.value.copy(
                                isLoading = false,
                                message = errorMessage
                            )
                        }
                    } else {
                        _state.value = _state.value.copy(
                            isLoading = false,
                            isEmailNotVerified = true,
                            message = "Email не подтверждён. Проверьте вашу почту."
                        )
                        user.sendEmailVerification().await()
                    }
                } else {
                    _state.value = _state.value.copy(
                        isLoading = false,
                        message = "Не удалось войти. Попробуйте ещё раз."
                    )
                }
            } catch (e: Exception) {
                val errorMessage = when {
                    // Общие ошибки входа
                    e.message?.contains("INVALID_LOGIN_CREDENTIALS", true) == true ||
                            e.message?.contains("INVALID_EMAIL", true) == true ||
                            e.message?.contains("USER_NOT_FOUND", true) == true ||
                            e.message?.contains("WRONG_PASSWORD", true) == true ||
                            e.message?.contains("The supplied auth credential is incorrect", true) == true ||
                            e.message?.contains("malformed", true) == true ||
                            e.message?.contains("has expired", true) == true -> {
                        "Неверный email или пароль"
                    }
                    // Слишком много запросов
                    e.message?.contains("TOO_MANY_ATTEMPTS", true) == true -> {
                        "Слишком много попыток. Попробуйте позже."
                    }
                    else -> {
                        // По умолчанию скрываем техническую ошибку
                        "Произошла ошибка. Проверьте подключение к интернету и попробуйте снова."
                    }
                }

                _state.value = _state.value.copy(
                    isLoading = false,
                    message = errorMessage
                )
            }
        }
    }
}