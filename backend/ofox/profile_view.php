<?php
ini_set('display_errors', 0);
ini_set('display_startup_errors', 0);
ini_set('log_errors', 1);

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once 'profile_check.php';

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $api_key = $_GET['api_key'] ?? null;
    $user_id = $_GET['user_id'] ?? null;

    if (!$api_key) {
        echo json_encode(['success' => false, 'error' => 'API ключ не предоставлен', 'code' => 'API_KEY_MISSING'], JSON_UNESCAPED_UNICODE);
        exit();
    }

    $database = new Database();
    $checker = new ProfileChecker($database);
    $profile_data = $checker->checkProfile($api_key, $user_id);

    // Если это чужой профиль, добавляем информацию о статусе заявки
    if ($user_id !== null && isset($profile_data['user_id'])) {
        // Получаем ID текущего пользователя
        $conn = $database->getConnection();
        $stmt = $conn->prepare("SELECT id FROM users WHERE api_key = ?");
        $stmt->execute([$api_key]);
        $current_user = $stmt->fetch(PDO::FETCH_ASSOC);
        
        if ($current_user) {
            $current_user_id = $current_user['id'];
            $target_user_id = $user_id;
            
            // Проверяем, являются ли пользователи друзьями
            $stmt = $conn->prepare("SELECT COUNT(*) as count FROM friends WHERE user_id = ? AND friend_id = ?");
            $stmt->execute([$current_user_id, $target_user_id]);
            $friendship = $stmt->fetch(PDO::FETCH_ASSOC);
            
            if ($friendship['count'] > 0) {
                $profile_data['friendship_status'] = 'friends';
                $profile_data['is_friend'] = true;
            } else {
                // Проверяем статус заявки в друзья
                // Проверяем, отправил ли текущий пользователь заявку
                $stmt = $conn->prepare("SELECT status FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
                $stmt->execute([$current_user_id, $target_user_id]);
                $sent_request = $stmt->fetch(PDO::FETCH_ASSOC);
                
                // Проверяем, получил ли текущий пользователь заявку
                $stmt = $conn->prepare("SELECT status FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
                $stmt->execute([$target_user_id, $current_user_id]);
                $received_request = $stmt->fetch(PDO::FETCH_ASSOC);
                
                if ($sent_request) {
                    $profile_data['friendship_status'] = 'request_sent';
                } elseif ($received_request) {
                    $profile_data['friendship_status'] = 'request_received';
                } else {
                    $profile_data['friendship_status'] = 'none';
                }
                $profile_data['is_friend'] = false;
            }
        }
        
        // Скрываем email для чужих профилей
        unset($profile_data['email']);
    } else {
        // Для собственного профиля
        $profile_data['friendship_status'] = 'own_profile';
    }

    echo json_encode($profile_data, JSON_UNESCAPED_UNICODE);
} else {
    echo json_encode(['success' => false, 'error' => 'Неподдерживаемый метод', 'code' => 'INVALID_METHOD'], JSON_UNESCAPED_UNICODE);
}
?>