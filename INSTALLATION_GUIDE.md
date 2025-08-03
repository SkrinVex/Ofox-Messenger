# Инструкция по установке системы заявок в друзья

## Проблема: Кнопки не отображаются

Если кнопки "Добавить в друзья" не отображаются при просмотре чужого профиля, выполните следующие шаги:

## 1. Проверка backend файлов

Убедитесь, что следующие файлы размещены на сервере:

### Обязательные файлы:
- `backend/profile_view.php` → скопировать в `ofox/profile_view.php` на сервере
- `backend/friend_request_handler.php` → скопировать в `ofox/friend_request_handler.php` на сервере  
- `backend/friends_handler.php` → скопировать в `ofox/friends_handler.php` на сервере

### Структура базы данных:
Выполните SQL из файла `backend/database_structure.sql`:

```sql
-- Таблица заявок в друзья
CREATE TABLE IF NOT EXISTS `friend_requests` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `sender_id` int(11) NOT NULL,
  `receiver_id` int(11) NOT NULL,
  `status` enum('pending','accepted','declined','removed') NOT NULL DEFAULT 'pending',
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` timestamp NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_request` (`sender_id`, `receiver_id`),
  KEY `idx_sender` (`sender_id`),
  KEY `idx_receiver` (`receiver_id`),
  KEY `idx_status` (`status`),
  FOREIGN KEY (`sender_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  FOREIGN KEY (`receiver_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Таблица друзей (если не существует)
CREATE TABLE IF NOT EXISTS `friends` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `friend_id` int(11) NOT NULL,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `unique_friendship` (`user_id`, `friend_id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_friend` (`friend_id`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  FOREIGN KEY (`friend_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Таблица уведомлений (если не существует)
CREATE TABLE IF NOT EXISTS `notifications` (
  `id` int(11) NOT NULL AUTO_INCREMENT,
  `user_id` int(11) NOT NULL,
  `title` varchar(255) NOT NULL,
  `content` text NOT NULL,
  `type` varchar(50) NOT NULL,
  `is_read` tinyint(1) NOT NULL DEFAULT 0,
  `created_at` timestamp NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_type` (`type`),
  KEY `idx_read` (`is_read`),
  FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
```

## 2. Проверка Android кода

Убедитесь, что в Android коде обновлены следующие файлы:
- `ApiService.kt` - добавлены новые методы API
- `ProfileViewModel.kt` - добавлены методы для работы с заявками
- `ProfileViewActivity.kt` - обновлен UI с новыми кнопками

## 3. Отладка

В текущей версии добавлена отладочная информация. При просмотре чужого профиля вы увидите:

```
Debug: friendship_status = 'none', user_id = 123
```

Возможные значения `friendship_status`:
- `"none"` - нет связи, должна показываться кнопка "Добавить в друзья"
- `"request_sent"` - заявка отправлена, кнопка "Отменить заявку"
- `"request_received"` - получена заявка, кнопки "Принять"/"Отклонить"
- `"friends"` - уже друзья, кнопки "Вы друзья"/"Написать"
- `null` или отсутствует - fallback к "none"

## 4. Проверка логов

### Android логи:
```bash
adb logcat | grep ProfileViewModel
```

Должны появиться сообщения:
```
ProfileViewModel: Loading user profile for userId: 123
ProfileViewModel: Profile loaded: user_id=123, friendship_status=none, is_friend=false
```

### PHP логи:
Проверьте error_log сервера на наличие ошибок в файлах:
- `profile_view.php`
- `friend_request_handler.php`

## 5. Тестирование API

Проверьте API напрямую:

```bash
curl "https://api.greenchat.kz/ofox/profile_view.php?api_key=YOUR_API_KEY&user_id=123"
```

Ответ должен содержать:
```json
{
  "success": true,
  "user_id": 123,
  "friendship_status": "none",
  "is_friend": false,
  ...
}
```

## 6. Частые проблемы

### Кнопки не появляются:
1. Проверьте, что `profile_view.php` обновлен на сервере
2. Убедитесь, что таблицы `friend_requests` и `friends` созданы
3. Проверьте права доступа к файлам PHP

### Ошибки в логах:
1. `Table 'friend_requests' doesn't exist` - выполните SQL из п.1
2. `Call to undefined function` - проверьте, что все файлы загружены
3. `Permission denied` - проверьте права доступа к файлам

### API возвращает старые данные:
1. Очистите кеш сервера
2. Проверьте, что файлы действительно обновились на сервере
3. Убедитесь, что нет дублирующих файлов

## 7. Удаление отладочной информации

После успешного тестирования удалите отладочные сообщения из `ProfileViewActivity.kt`:

```kotlin
// Удалить эти строки:
Text(
    text = "Debug: friendship_status = '${profile.friendship_status}', user_id = ${profile.user_id}",
    color = Color.Yellow,
    fontSize = 12.sp
)
```

## Поддержка

Если проблема не решается:
1. Проверьте все пункты выше
2. Соберите логи Android и PHP
3. Проверьте ответ API через curl
4. Убедитесь, что база данных содержит правильную структуру