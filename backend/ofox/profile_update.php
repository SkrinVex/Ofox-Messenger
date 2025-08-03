<?php
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once '../auth/config.php';

$maxFileSize = 3 * 1024 * 1024; // 3 MB
$allowedTypes = ['image/jpeg', 'image/png', 'image/webp'];

function compressImage($source, $destination, $quality = 80) {
    $info = getimagesize($source);
    if ($info['mime'] == 'image/jpeg') {
        $image = imagecreatefromjpeg($source);
        imagejpeg($image, $destination, $quality);
    } elseif ($info['mime'] == 'image/png') {
        $image = imagecreatefrompng($source);
        imagepng($image, $destination, 8);
    } elseif ($info['mime'] == 'image/webp') {
        $image = imagecreatefromwebp($source);
        imagewebp($image, $destination, $quality);
    } else {
        return false;
    }
    imagedestroy($image);
    return true;
}

function validateField($field, $value) {
    switch ($field) {
        case 'email':
            return filter_var($value, FILTER_VALIDATE_EMAIL);
        case 'username':
            // Убираем @ если есть в начале для валидации
            $cleanValue = ltrim($value, '@');
            return preg_match('/^[a-zA-Z0-9_\\-]{3,32}$/u', $cleanValue);
        case 'nickname':
            return preg_match('/^[a-zA-Z0-9_\\-а-яёА-ЯЁ ]{2,32}$/u', $value);
        case 'birthday':
    		return preg_match('/^\\d{2}\\.\\d{2}\\.\\d{4}$/', $value);
        case 'status':
            return mb_strlen($value) <= 100;
        case 'bio':
            return mb_strlen($value) <= 500;
        default:
            return true;
    }
}

function isValidImageFile($filePath) {
    // Проверяем файл через getimagesize() - более надежно чем MIME type
    $imageInfo = getimagesize($filePath);
    if ($imageInfo === false) {
        return false;
    }
    
    $allowedImageTypes = [
        IMAGETYPE_JPEG,
        IMAGETYPE_PNG,
        IMAGETYPE_WEBP
    ];
    
    return in_array($imageInfo[2], $allowedImageTypes);
}

function saveImage($file, $userId, $type) {
    global $maxFileSize, $allowedTypes;
    
    if ($file['error'] !== UPLOAD_ERR_OK) {
        return [false, 'Ошибка загрузки файла'];
    }
    
    if ($file['size'] > $maxFileSize) {
        return [false, 'Файл слишком большой (максимум 3 МБ)'];
    }
    
    // Дополнительная проверка на пустой файл
    if ($file['size'] == 0) {
        return [false, 'Файл пустой'];
    }
    
    // Проверяем временный файл на валидность изображения
    if (!isValidImageFile($file['tmp_name'])) {
        return [false, 'Недопустимый тип файла. Разрешены только JPEG, PNG, WebP'];
    }
    
    // Получаем реальный MIME type через finfo
    $finfo = finfo_open(FILEINFO_MIME_TYPE);
    $realMimeType = finfo_file($finfo, $file['tmp_name']);
    finfo_close($finfo);
    
    if (!in_array($realMimeType, $allowedTypes)) {
        return [false, 'Недопустимый тип файла. Разрешены только JPEG, PNG, WebP'];
    }

    // Определяем расширение по реальному MIME типу
    $mimeToExt = [
        'image/jpeg' => 'jpg',
        'image/png' => 'png',
        'image/webp' => 'webp'
    ];
    
    $ext = $mimeToExt[$realMimeType] ?? 'jpg';
    
    $uploadDir = __DIR__ . '/../uploads/';
    if (!is_dir($uploadDir)) {
        mkdir($uploadDir, 0777, true);
    }
    
    $filename = $type . "_user_" . $userId . "_" . time() . "." . $ext;
    $destination = $uploadDir . $filename;

    if (!move_uploaded_file($file['tmp_name'], $destination)) {
        return [false, 'Не удалось сохранить файл'];
    }

    // Сжимаем изображение
    if (!compressImage($destination, $destination, 80)) {
        // Если сжатие не удалось, удаляем файл и возвращаем ошибку
        unlink($destination);
        return [false, 'Ошибка обработки изображения'];
    }

    // Вернуть относительный путь для хранения в БД
    return [true, '/uploads/' . $filename];
}

// Получение данных (JSON или form-data)
$input = [];
if (isset($_SERVER['CONTENT_TYPE']) && strpos($_SERVER['CONTENT_TYPE'], 'application/json') !== false) {
    $input = json_decode(file_get_contents('php://input'), true);
} else {
    $input = $_POST;
}

$api_key = $input['api_key'] ?? null;
if (!$api_key) {
    echo json_encode(['success' => false, 'error' => 'API ключ не предоставлен']);
    exit();
}

try {
    $db = new Database();
    $conn = $db->getConnection();

    // Получаем пользователя по api_key
    $stmt = $conn->prepare("SELECT * FROM users WHERE api_key = ?");
    $stmt->execute([$api_key]);
    $user = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$user) {
        echo json_encode(['success' => false, 'error' => 'Пользователь не найден']);
        exit();
    }

    $fields = [
        'username', 'nickname', 'email', 'birthday', 'status', 'bio'
    ];
    $update = [];
    $params = [];

    foreach ($fields as $field) {
        if (isset($input[$field]) && $input[$field] !== '') {
            $value = trim($input[$field]);
            
            // Специальная обработка для username - убираем @ если есть
            if ($field === 'username' && str_starts_with($value, '@')) {
                $value = substr($value, 1);
            }
            
            if (!validateField($field, $value)) {
                $errorMessages = [
                    'email' => 'Некорректный email адрес',
                    'username' => 'Имя пользователя должно содержать 3-32 символа (буквы, цифры, _, -)',
                    'nickname' => 'Никнейм должен содержать 2-32 символа',
                    'birthday' => 'Некорректная дата рождения (формат: DD.MM.YYYY)',
                    'status' => 'Статус не должен превышать 100 символов',
                    'bio' => 'Биография не должна превышать 500 символов'
                ];
                echo json_encode([
                    'success' => false, 
                    'error' => $errorMessages[$field] ?? "Некорректное значение для $field"
                ]);
                exit();
            }
            
            // Проверяем уникальность username и email
            if ($field === 'username' || $field === 'email') {
                $checkStmt = $conn->prepare("SELECT id FROM users WHERE $field = ? AND id != ?");
                $checkStmt->execute([$value, $user['id']]);
                if ($checkStmt->fetch()) {
                    $fieldName = $field === 'username' ? 'имя пользователя' : 'email';
                    echo json_encode([
                        'success' => false, 
                        'error' => "Этот $fieldName уже используется"
                    ]);
                    exit();
                }
            }
            
            $update[] = "$field = ?";
            $params[] = $value;
        }
    }

    // Обработка profile_photo
    if (isset($_FILES['profile_photo'])) {
        list($ok, $result) = saveImage($_FILES['profile_photo'], $user['id'], 'profile_photo');
        if (!$ok) {
            echo json_encode(['success' => false, 'error' => $result]);
            exit();
        }
        
        // Удаляем старое фото если есть
        if (!empty($user['profile_photo'])) {
            $oldPhotoPath = __DIR__ . '/..' . $user['profile_photo'];
            if (file_exists($oldPhotoPath)) {
                unlink($oldPhotoPath);
            }
        }
        
        $update[] = "profile_photo = ?";
        $params[] = $result;
    }

    // Обработка background_photo
    if (isset($_FILES['background_photo'])) {
        list($ok, $result) = saveImage($_FILES['background_photo'], $user['id'], 'background_photo');
        if (!$ok) {
            echo json_encode(['success' => false, 'error' => $result]);
            exit();
        }
        
        // Удаляем старое фото если есть
        if (!empty($user['background_photo'])) {
            $oldPhotoPath = __DIR__ . '/..' . $user['background_photo'];
            if (file_exists($oldPhotoPath)) {
                unlink($oldPhotoPath);
            }
        }
        
        $update[] = "background_photo = ?";
        $params[] = $result;
    }

    // Обновляем время последнего изменения профиля
    $update[] = "updated_at = NOW()";

    if (!empty($update)) {
        $params[] = $user['id'];
        $sql = "UPDATE users SET " . implode(', ', $update) . " WHERE id = ?";
        $stmt = $conn->prepare($sql);
        $stmt->execute($params);
        
        // Логируем изменения
        $changedFields = array_keys(array_filter($input, function($value, $key) use ($fields) {
            return in_array($key, $fields) && $value !== '';
        }, ARRAY_FILTER_USE_BOTH));
        
        if (isset($_FILES['profile_photo'])) {
            $changedFields[] = 'profile_photo';
        }
        
        if (isset($_FILES['background_photo'])) {
            $changedFields[] = 'background_photo';
        }
        
        $logStmt = $conn->prepare("INSERT INTO profile_updates_log (user_id, changed_fields, ip_address, user_agent, created_at) VALUES (?, ?, ?, ?, NOW())");
        $logStmt->execute([
            $user['id'],
            json_encode($changedFields),
            $_SERVER['REMOTE_ADDR'] ?? 'unknown',
            $_SERVER['HTTP_USER_AGENT'] ?? 'unknown'
        ]);
    }

    echo json_encode([
        'success' => true, 
        'message' => 'Профиль успешно обновлен',
        'updated_fields' => count($update) - 1 // -1 потому что updated_at всегда добавляется
    ]);

} catch (PDOException $e) {
    error_log("Database error in profile_update.php: " . $e->getMessage());
    echo json_encode(['success' => false, 'error' => 'Ошибка базы данных']);
} catch (Exception $e) {
    error_log("General error in profile_update.php: " . $e->getMessage());
    echo json_encode(['success' => false, 'error' => 'Произошла ошибка сервера']);
}
?>