<?php
ini_set('display_errors', 0);
ini_set('display_startup_errors', 0);
ini_set('log_errors', 1);

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once '../auth/config.php';

$database = new Database();
$conn = $database->getConnection();

$action = $_POST['action'] ?? $_GET['action'] ?? '';
$api_key = $_POST['api_key'] ?? $_GET['api_key'] ?? '';
$target_id = $_POST['target_id'] ?? $_GET['target_id'] ?? '';

if (!$api_key || !$target_id) {
    echo json_encode(['success' => false, 'error' => 'Не хватает данных'], JSON_UNESCAPED_UNICODE);
    exit();
}

// Получаем ID отправителя
$stmt = $conn->prepare("SELECT id FROM users WHERE api_key = ?");
$stmt->execute([$api_key]);
$sender = $stmt->fetch(PDO::FETCH_ASSOC);
if (!$sender) {
    echo json_encode(['success' => false, 'error' => 'Неверный API ключ'], JSON_UNESCAPED_UNICODE);
    exit();
}
$sender_id = $sender['id'];

// Проверяем, что target_id существует
$stmt = $conn->prepare("SELECT id FROM users WHERE id = ?");
$stmt->execute([$target_id]);
if (!$stmt->fetch()) {
    echo json_encode(['success' => false, 'error' => 'Пользователь не найден'], JSON_UNESCAPED_UNICODE);
    exit();
}

// Проверяем, что пользователь не пытается добавить себя в друзья
if ($sender_id == $target_id) {
    echo json_encode(['success' => false, 'error' => 'Нельзя добавить себя в друзья'], JSON_UNESCAPED_UNICODE);
    exit();
}

try {
    $conn->beginTransaction();

    if ($action === 'send_request') {
        // Проверяем, нет ли уже заявки
        $stmt = $conn->prepare("SELECT * FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
        $stmt->execute([$sender_id, $target_id]);
        if ($stmt->fetch()) {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Заявка уже отправлена'], JSON_UNESCAPED_UNICODE);
            exit();
        }

        // Проверяем, не являются ли уже друзьями
        $stmt = $conn->prepare("SELECT COUNT(*) as count FROM friends WHERE user_id = ? AND friend_id = ?");
        $stmt->execute([$sender_id, $target_id]);
        $friendship = $stmt->fetch(PDO::FETCH_ASSOC);
        if ($friendship['count'] > 0) {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Вы уже друзья'], JSON_UNESCAPED_UNICODE);
            exit();
        }

        // Проверяем, нет ли встречной заявки
        $stmt = $conn->prepare("SELECT * FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
        $stmt->execute([$target_id, $sender_id]);
        $reverse_request = $stmt->fetch();
        
        if ($reverse_request) {
            // Если есть встречная заявка, автоматически принимаем дружбу
            $stmt = $conn->prepare("UPDATE friend_requests SET status = 'accepted', updated_at = NOW() WHERE id = ?");
            $stmt->execute([$reverse_request['id']]);

            // Добавляем в друзья (взаимная связь)
            $stmt = $conn->prepare("INSERT INTO friends (user_id, friend_id, created_at) VALUES (?, ?, NOW()), (?, ?, NOW())");
            $stmt->execute([$sender_id, $target_id, $target_id, $sender_id]);

            // Создаём уведомления для обоих пользователей
            $stmt = $conn->prepare("INSERT INTO notifications (user_id, title, content, type, created_at) VALUES (?, ?, ?, 'friend_accepted', NOW())");
            $stmt->execute([$target_id, 'Новый друг', 'Вы стали друзьями!']);
            
            $stmt = $conn->prepare("INSERT INTO notifications (user_id, title, content, type, created_at) VALUES (?, ?, ?, 'friend_accepted', NOW())");
            $stmt->execute([$sender_id, 'Новый друг', 'Вы стали друзьями!']);

            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Вы стали друзьями!'], JSON_UNESCAPED_UNICODE);
        } else {
            // Создаём новую заявку
            $stmt = $conn->prepare("INSERT INTO friend_requests (sender_id, receiver_id, status, created_at) VALUES (?, ?, 'pending', NOW())");
            $stmt->execute([$sender_id, $target_id]);

            // Создаём уведомление для получателя
            $stmt = $conn->prepare("INSERT INTO notifications (user_id, title, content, type, created_at) VALUES (?, ?, ?, 'friend_request', NOW())");
            $stmt->execute([$target_id, 'Новая заявка в друзья', 'Пользователь отправил вам запрос в друзья.']);

            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Заявка отправлена'], JSON_UNESCAPED_UNICODE);
        }

    } elseif ($action === 'cancel_request') {
        // Отмена заявки (только отправитель может отменить свою заявку)
        $stmt = $conn->prepare("DELETE FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
        $stmt->execute([$sender_id, $target_id]);
        
        if ($stmt->rowCount() > 0) {
            // Удаляем связанные уведомления
            $stmt = $conn->prepare("DELETE FROM notifications WHERE user_id = ? AND type = 'friend_request' AND created_at >= (SELECT MAX(created_at) FROM friend_requests WHERE sender_id = ? AND receiver_id = ?)");
            $stmt->execute([$target_id, $sender_id, $target_id]);
            
            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Заявка отменена'], JSON_UNESCAPED_UNICODE);
        } else {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Заявка не найдена'], JSON_UNESCAPED_UNICODE);
        }

    } elseif ($action === 'accept_request') {
        // Принятие заявки (только получатель может принять)
        $stmt = $conn->prepare("UPDATE friend_requests SET status = 'accepted', updated_at = NOW() WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
        $stmt->execute([$target_id, $sender_id]);

        if ($stmt->rowCount() > 0) {
            // Добавляем в друзья (взаимная связь)
            $stmt = $conn->prepare("INSERT INTO friends (user_id, friend_id, created_at) VALUES (?, ?, NOW()), (?, ?, NOW())");
            $stmt->execute([$sender_id, $target_id, $target_id, $sender_id]);

            // Создаём уведомление для отправителя
            $stmt = $conn->prepare("INSERT INTO notifications (user_id, title, content, type, created_at) VALUES (?, ?, ?, 'friend_accepted', NOW())");
            $stmt->execute([$target_id, 'Заявка принята', 'Ваша заявка в друзья была принята.']);

            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Заявка принята'], JSON_UNESCAPED_UNICODE);
        } else {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Заявка не найдена'], JSON_UNESCAPED_UNICODE);
        }

    } elseif ($action === 'decline_request') {
        // Отклонение заявки (только получатель может отклонить)
        $stmt = $conn->prepare("UPDATE friend_requests SET status = 'declined', updated_at = NOW() WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
        $stmt->execute([$target_id, $sender_id]);

        if ($stmt->rowCount() > 0) {
            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Заявка отклонена'], JSON_UNESCAPED_UNICODE);
        } else {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Заявка не найдена'], JSON_UNESCAPED_UNICODE);
        }

    } elseif ($action === 'remove_friend') {
        // Удаление из друзей
        $stmt = $conn->prepare("DELETE FROM friends WHERE (user_id = ? AND friend_id = ?) OR (user_id = ? AND friend_id = ?)");
        $stmt->execute([$sender_id, $target_id, $target_id, $sender_id]);
        
        if ($stmt->rowCount() > 0) {
            // Также удаляем записи о принятых заявках
            $stmt = $conn->prepare("UPDATE friend_requests SET status = 'removed', updated_at = NOW() WHERE ((sender_id = ? AND receiver_id = ?) OR (sender_id = ? AND receiver_id = ?)) AND status = 'accepted'");
            $stmt->execute([$sender_id, $target_id, $target_id, $sender_id]);
            
            $conn->commit();
            echo json_encode(['success' => true, 'message' => 'Пользователь удален из друзей'], JSON_UNESCAPED_UNICODE);
        } else {
            $conn->rollBack();
            echo json_encode(['success' => false, 'error' => 'Пользователь не найден в друзьях'], JSON_UNESCAPED_UNICODE);
        }

    } else {
        $conn->rollBack();
        echo json_encode(['success' => false, 'error' => 'Неизвестное действие'], JSON_UNESCAPED_UNICODE);
    }
} catch (PDOException $e) {
    $conn->rollBack();
    error_log("Database error in friend_request_handler: " . $e->getMessage());
    echo json_encode(['success' => false, 'error' => 'Ошибка базы данных'], JSON_UNESCAPED_UNICODE);
} catch (Exception $e) {
    $conn->rollBack();
    error_log("General error in friend_request_handler: " . $e->getMessage());
    echo json_encode(['success' => false, 'error' => 'Внутренняя ошибка сервера'], JSON_UNESCAPED_UNICODE);
}
?>