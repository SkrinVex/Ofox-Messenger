# OFOX Profile Check API

## Описание
API для проверки полноты профиля пользователя. Определяет незаполненные поля и предоставляет рекомендации по улучшению профиля.

## Endpoint
`POST /ofox/profile_check.php` или `GET /ofox/profile_check.php`

## Параметры

### POST запрос
```json
{
    "api_key": "ваш_api_ключ_пользователя"
}
```

### GET запрос
```
/ofox/profile_check.php?api_key=ваш_api_ключ_пользователя
```

## Ответ

### Успешный ответ
```json
{
    "success": true,
    "user_id": 123,
    "profile_completion": 75,
    "profile_status": "GOOD",
    "empty_fields": [
        {
            "field": "profile_photo",
            "name": "Фото профиля",
            "required": false,
            "priority": 7
        },
        {
            "field": "bio",
            "name": "О себе",
            "required": false,
            "priority": 4
        }
    ],
    "has_empty_fields": true,
    "total_fields": 8,
    "filled_fields": 6,
    "recommendations": [
        "Загрузите фото профиля, чтобы другие пользователи могли вас узнать",
        "Расскажите о себе в разделе \"О себе\""
    ],
    "next_action": "UPLOAD_PHOTO"
}
```

### Ошибка
```json
{
    "success": false,
    "error": "Пользователь не найден",
    "code": "USER_NOT_FOUND"
}
```

## Статусы профиля
- `COMPLETE` (90-100%) - Профиль полностью заполнен
- `GOOD` (70-89%) - Профиль хорошо заполнен
- `PARTIAL` (50-69%) - Профиль частично заполнен
- `BASIC` (30-49%) - Базовый профиль
- `EMPTY` (0-29%) - Пустой профиль

## Следующие действия (next_action)
- `PROFILE_COMPLETE` - Профиль полностью заполнен
- `SET_USERNAME` - Нужно установить имя пользователя
- `SET_NICKNAME` - Нужно установить никнейм
- `UPLOAD_PHOTO` - Нужно загрузить фото профиля
- `SET_BIRTHDAY` - Нужно указать дату рождения
- `SET_STATUS` - Нужно установить статус
- `WRITE_BIO` - Нужно написать о себе
- `UPLOAD_BACKGROUND` - Нужно загрузить фоновое изображение
- `COMPLETE_PROFILE` - Нужно дополнить профиль

## Коды ошибок
- `MISSING_API_KEY` - API ключ не предоставлен
- `USER_NOT_FOUND` - Пользователь не найден
- `DB_ERROR` - Ошибка базы данных
- `INVALID_METHOD` - Неподдерживаемый метод запроса

## Пример использования в Android Studio

```kotlin
// Проверка профиля пользователя
fun checkUserProfile(apiKey: String) {
    val url = "https://api.greenchat.kz/ofox/profile_check.php"
    
    val requestBody = JSONObject().apply {
        put("api_key", apiKey)
    }
    
    val request = Request.Builder()
        .url(url)
        .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
        .build()
    
    client.newCall(request).enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            val responseBody = response.body?.string()
            val jsonResponse = JSONObject(responseBody)
            
            if (jsonResponse.getBoolean("success")) {
                val profileCompletion = jsonResponse.getInt("profile_completion")
                val hasEmptyFields = jsonResponse.getBoolean("has_empty_fields")
                val nextAction = jsonResponse.getString("next_action")
                
                // Обработка результата
                when (nextAction) {
                    "SET_USERNAME" -> navigateToUsernameSetup()
                    "UPLOAD_PHOTO" -> navigateToPhotoUpload()
                    "WRITE_BIO" -> navigateToBioEditor()
                    "PROFILE_COMPLETE" -> showProfileComplete()
                    else -> showProfileIncomplete(profileCompletion)
                }
            } else {
                // Обработка ошибки
                val error = jsonResponse.getString("error")
                showError(error)
            }
        }
        
        override fun onFailure(call: Call, e: IOException) {
            showError("Ошибка сети: ${e.message}")
        }
    })
}
```

## Логика работы
1. API получает API ключ пользователя
2. Проверяет существование пользователя в базе данных
3. Анализирует заполненность всех полей профиля
4. Вычисляет процент заполнения профиля
5. Определяет приоритетные незаполненные поля
6. Возвращает структурированный ответ с рекомендациями 