<?php
ini_set('display_errors', 0);
ini_set('display_startup_errors', 0);
ini_set('log_errors', 1);

header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, POST, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

require_once '../auth/config.php';

class FriendsHandler {
    private $conn;
    
    public function __construct($database) {
        $this->conn = $database->getConnection();
    }
    
    public function getFriendsAndUsers($api_key, $search_query = null) {
        try {
            $stmt = $this->conn->prepare("SELECT id FROM users WHERE api_key = ?");
            $stmt->execute([$api_key]);
            $user = $stmt->fetch(PDO::FETCH_ASSOC);
            
            if (!$user) {
                return [
                    'success' => false,
                    'error' => 'Пользователь не найден',
                    'code' => 'USER_NOT_FOUND'
                ];
            }

            $user_id = $user['id'];

            // Получаем друзей
            $friends_query = "SELECT u.id, u.username, u.nickname, u.email, u.profile_photo, u.status 
                            FROM users u 
                            JOIN friends f ON u.id = f.friend_id 
                            WHERE f.user_id = ?";
            $friends_params = [$user_id];
            
            if ($search_query) {
                $friends_query .= " AND (u.username LIKE ? OR u.nickname LIKE ? OR u.email LIKE ?)";
                $search_pattern = "%$search_query%";
                $friends_params[] = $search_pattern;
                $friends_params[] = $search_pattern;
                $friends_params[] = $search_pattern;
            }
            
            $friends_query .= " ORDER BY u.nickname ASC, u.username ASC";
            
            $stmt = $this->conn->prepare($friends_query);
            $stmt->execute($friends_params);
            $friends = $stmt->fetchAll(PDO::FETCH_ASSOC);

            // Получаем других пользователей (исключая друзей и самого пользователя)
            $users_query = "SELECT u.id, u.username, u.nickname, u.email, u.profile_photo, u.status 
                           FROM users u 
                           WHERE u.id != ? AND u.id NOT IN (
                               SELECT friend_id FROM friends WHERE user_id = ?
                           )";
            $users_params = [$user_id, $user_id];
            
            if ($search_query) {
                $users_query .= " AND (u.username LIKE ? OR u.nickname LIKE ? OR u.email LIKE ?)";
                $search_pattern = "%$search_query%";
                $users_params[] = $search_pattern;
                $users_params[] = $search_pattern;
                $users_params[] = $search_pattern;
            }
            
            $users_query .= " ORDER BY u.nickname ASC, u.username ASC LIMIT 50";
            
            $stmt = $this->conn->prepare($users_query);
            $stmt->execute($users_params);
            $other_users = $stmt->fetchAll(PDO::FETCH_ASSOC);
            
            // Добавляем информацию о статусе заявок для других пользователей
            foreach ($other_users as &$other_user) {
                $target_id = $other_user['id'];
                
                // Проверяем, отправил ли текущий пользователь заявку
                $stmt = $this->conn->prepare("SELECT COUNT(*) as count FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
                $stmt->execute([$user_id, $target_id]);
                $sent_request = $stmt->fetch(PDO::FETCH_ASSOC);
                
                // Проверяем, получил ли текущий пользователь заявку
                $stmt = $this->conn->prepare("SELECT COUNT(*) as count FROM friend_requests WHERE sender_id = ? AND receiver_id = ? AND status = 'pending'");
                $stmt->execute([$target_id, $user_id]);
                $received_request = $stmt->fetch(PDO::FETCH_ASSOC);
                
                if ($sent_request['count'] > 0) {
                    $other_user['friendship_status'] = 'request_sent';
                } elseif ($received_request['count'] > 0) {
                    $other_user['friendship_status'] = 'request_received';
                } else {
                    $other_user['friendship_status'] = 'none';
                }
                
                // Скрываем email для других пользователей
                unset($other_user['email']);
            }
            
            // Скрываем email для друзей тоже
            foreach ($friends as &$friend) {
                unset($friend['email']);
            }
            
            return [
                'success' => true,
                'friends' => $friends,
                'friends_count' => count($friends),
                'other_users' => $other_users,
                'other_users_count' => count($other_users),
                'search_query' => $search_query
            ];
        } catch (PDOException $e) {
            error_log("Database error in FriendsHandler: " . $e->getMessage());
            return [
                'success' => false,
                'error' => 'Ошибка базы данных',
                'code' => 'DB_ERROR'
            ];
        }
    }
    
    public function getFriendRequests($api_key) {
        try {
            $stmt = $this->conn->prepare("SELECT id FROM users WHERE api_key = ?");
            $stmt->execute([$api_key]);
            $user = $stmt->fetch(PDO::FETCH_ASSOC);
            
            if (!$user) {
                return [
                    'success' => false,
                    'error' => 'Пользователь не найден',
                    'code' => 'USER_NOT_FOUND'
                ];
            }

            $user_id = $user['id'];

            // Получаем входящие заявки
            $incoming_query = "SELECT u.id, u.username, u.nickname, u.profile_photo, u.status, fr.created_at
                              FROM users u 
                              JOIN friend_requests fr ON u.id = fr.sender_id 
                              WHERE fr.receiver_id = ? AND fr.status = 'pending'
                              ORDER BY fr.created_at DESC";
            
            $stmt = $this->conn->prepare($incoming_query);
            $stmt->execute([$user_id]);
            $incoming_requests = $stmt->fetchAll(PDO::FETCH_ASSOC);

            // Получаем исходящие заявки
            $outgoing_query = "SELECT u.id, u.username, u.nickname, u.profile_photo, u.status, fr.created_at
                              FROM users u 
                              JOIN friend_requests fr ON u.id = fr.receiver_id 
                              WHERE fr.sender_id = ? AND fr.status = 'pending'
                              ORDER BY fr.created_at DESC";
            
            $stmt = $this->conn->prepare($outgoing_query);
            $stmt->execute([$user_id]);
            $outgoing_requests = $stmt->fetchAll(PDO::FETCH_ASSOC);
            
            return [
                'success' => true,
                'incoming_requests' => $incoming_requests,
                'incoming_count' => count($incoming_requests),
                'outgoing_requests' => $outgoing_requests,
                'outgoing_count' => count($outgoing_requests)
            ];
        } catch (PDOException $e) {
            error_log("Database error in getFriendRequests: " . $e->getMessage());
            return [
                'success' => false,
                'error' => 'Ошибка базы данных',
                'code' => 'DB_ERROR'
            ];
        }
    }
}

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $api_key = $_GET['api_key'] ?? null;
    $search_query = $_GET['search_query'] ?? null;
    $action = $_GET['action'] ?? 'get_friends';
    
    if (!$api_key) {
        echo json_encode(['success' => false, 'error' => 'API ключ не предоставлен', 'code' => 'API_KEY_MISSING'], JSON_UNESCAPED_UNICODE);
        exit();
    }

    $database = new Database();
    $handler = new FriendsHandler($database);
    
    if ($action === 'get_requests') {
        echo json_encode($handler->getFriendRequests($api_key), JSON_UNESCAPED_UNICODE);
    } else {
        echo json_encode($handler->getFriendsAndUsers($api_key, $search_query), JSON_UNESCAPED_UNICODE);
    }
} else {
    echo json_encode(['success' => false, 'error' => 'Неподдерживаемый метод', 'code' => 'INVALID_METHOD'], JSON_UNESCAPED_UNICODE);
}
?>