<?php
header('Content-Type: application/json; charset=utf-8');
header('Access-Control-Allow-Origin: *');
header('Access-Control-Allow-Methods: GET, OPTIONS');
header('Access-Control-Allow-Headers: Content-Type, Authorization');

if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
    http_response_code(200);
    exit();
}

define('PROFILE_CHECK_INTERNAL', true);
require_once 'profile_check.php';

class MainPageController {
    private $conn;
    
    public function __construct($database) {
        $this->conn = $database->getConnection();
    }

    private function getNewsFeed() {
        $query = "SELECT id, title, content, date FROM news ORDER BY date DESC, id DESC";
        try {
            $stmt = $this->conn->prepare($query);
            $stmt->execute();
            return $stmt->fetchAll(PDO::FETCH_ASSOC);
        } catch (PDOException $e) {
            return [];
        }
    }

    private function getNotifications($userId) {
        $query = "SELECT id, title, content, type, is_read, created_at 
                  FROM notifications 
                  WHERE user_id = ? 
                  ORDER BY created_at DESC, id DESC";
        try {
            $stmt = $this->conn->prepare($query);
            $stmt->execute([$userId]);
            return $stmt->fetchAll(PDO::FETCH_ASSOC);
        } catch (PDOException $e) {
            return [];
        }
    }

    public function getData($api_key) {
        $checker = new ProfileChecker(new Database());
        $profileData = $checker->checkProfile($api_key);

        if (!$profileData['success']) {
            return $profileData;
        }

        $newsFeed = $this->getNewsFeed();
        $notifications = $this->getNotifications($profileData['user_id']);

        return [
            'success' => true,
            'profile' => $profileData,
            'news_feed' => $newsFeed,
            'notifications' => $notifications
        ];
    }
}

if ($_SERVER['REQUEST_METHOD'] === 'GET') {
    $api_key = $_GET['api_key'] ?? null;
    if (!$api_key) {
        echo json_encode(['success' => false, 'error' => 'API ключ не предоставлен', 'code' => 'API_KEY_MISSING'], JSON_UNESCAPED_UNICODE);
        exit();
    }

    $database = new Database();
    $controller = new MainPageController($database);
    echo json_encode($controller->getData($api_key), JSON_UNESCAPED_UNICODE);
} else {
    echo json_encode(['success' => false, 'error' => 'Неподдерживаемый метод', 'code' => 'INVALID_METHOD'], JSON_UNESCAPED_UNICODE);
}
