<?php
// Логируем ошибки в стандартный лог Apache, но не выводим их в ответ
ini_set('display_errors', 0);
ini_set('display_startup_errors', 0);
ini_set('log_errors', 1);

if (!defined('PROFILE_CHECK_INTERNAL')) {
    header('Content-Type: application/json; charset=utf-8');
    header('Access-Control-Allow-Origin: *');
    header('Access-Control-Allow-Methods: POST, GET, OPTIONS');
    header('Access-Control-Allow-Headers: Content-Type, Authorization');

    if ($_SERVER['REQUEST_METHOD'] === 'OPTIONS') {
        http_response_code(200);
        exit();
    }
}

require_once '../auth/config.php';

class ProfileChecker {
    private $conn;

    public function __construct($database) {
        $this->conn = $database->getConnection();
    }

    public function checkProfile($api_key, $user_id = null) {
        try {
            if ($user_id !== null) {
                $stmt = $this->conn->prepare("SELECT * FROM users WHERE id = ?");
                $stmt->execute([$user_id]);
            } else {
                $stmt = $this->conn->prepare("SELECT * FROM users WHERE api_key = ?");
                $stmt->execute([$api_key]);
            }

            $user = $stmt->fetch(PDO::FETCH_ASSOC);

            if (!$user) {
                return [
                    'success' => false,
                    'error' => 'Пользователь не найден',
                    'code' => 'USER_NOT_FOUND'
                ];
            }

            $empty_fields = [];
            $profile_completion = 0;
            $total_fields = 0;

            $profile_fields = [
                'username' => 'Имя пользователя',
                'nickname' => 'Никнейм',
                'email' => 'Email',
                'birthday' => 'Дата рождения',
                'profile_photo' => 'Фото профиля',
                'background_photo' => 'Фон профиля',
                'status' => 'Статус',
                'bio' => 'О себе'
            ];

            foreach ($profile_fields as $field => $field_name) {
                $total_fields++;
                if (empty($user[$field]) || $user[$field] === null) {
                    $empty_fields[] = [
                        'field' => $field,
                        'name' => $field_name,
                        'required' => in_array($field, ['username', 'nickname', 'email']),
                        'priority' => $this->getFieldPriority($field)
                    ];
                } else {
                    $profile_completion++;
                }
            }

            usort($empty_fields, function ($a, $b) {
                return $b['priority'] - $a['priority'];
            });

            $completion_percentage = round(($profile_completion / $total_fields) * 100);
            $profile_status = $this->getProfileStatus($completion_percentage);

            return [
                'success' => true,
                'user_id' => $user['id'],
                'username' => $user['username'],
                'nickname' => $user['nickname'],
                'email' => $user['email'],
                'birthday' => $user['birthday'],
                'status' => $user['status'],
                'bio' => $user['bio'],
                'profile_completion' => $completion_percentage,
                'profile_status' => $profile_status,
                'empty_fields' => $empty_fields,
                'has_empty_fields' => !empty($empty_fields),
                'total_fields' => $total_fields,
                'filled_fields' => $profile_completion,
                'recommendations' => $this->getRecommendations($empty_fields),
                'next_action' => $this->getNextAction($empty_fields),
                'profile_photo' => $user['profile_photo'],
                'background_photo' => $user['background_photo']
            ];
        } catch (PDOException $e) {
            return [
                'success' => false,
                'error' => 'Ошибка базы данных',
                'code' => 'DB_ERROR',
                'details' => $e->getMessage()
            ];
        }
    }

    private function getFieldPriority($field) {
        $priorities = [
            'username' => 10,
            'nickname' => 9,
            'email' => 8,
            'profile_photo' => 7,
            'birthday' => 6,
            'status' => 5,
            'bio' => 4,
            'background_photo' => 3
        ];
        return $priorities[$field] ?? 1;
    }

    private function getProfileStatus($completion_percentage) {
        if ($completion_percentage >= 90) return 'COMPLETE';
        if ($completion_percentage >= 70) return 'GOOD';
        if ($completion_percentage >= 50) return 'PARTIAL';
        if ($completion_percentage >= 30) return 'BASIC';
        return 'EMPTY';
    }

    private function getRecommendations($empty_fields) {
        $recommendations = [];
        foreach ($empty_fields as $field) {
            switch ($field['field']) {
                case 'username': $recommendations[] = 'Добавьте уникальное имя пользователя'; break;
                case 'nickname': $recommendations[] = 'Установите никнейм'; break;
                case 'profile_photo': $recommendations[] = 'Загрузите фото профиля'; break;
                case 'birthday': $recommendations[] = 'Укажите дату рождения'; break;
                case 'status': $recommendations[] = 'Добавьте статус'; break;
                case 'bio': $recommendations[] = 'Расскажите о себе'; break;
                case 'background_photo': $recommendations[] = 'Добавьте фоновое изображение'; break;
            }
        }
        return $recommendations;
    }

    private function getNextAction($empty_fields) {
        foreach ($empty_fields as $field) {
            if ($field['field'] === 'username') {
                return 'SET_USERNAME';
            }
        }
        return 'PROFILE_COMPLETE';
    }
}

if (!defined('PROFILE_CHECK_INTERNAL')) {
    if ($_SERVER['REQUEST_METHOD'] === 'POST') {
        $input = json_decode(file_get_contents('php://input'), true);
        $api_key = $input['api_key'] ?? $_POST['api_key'] ?? null;
        $database = new Database();
        $checker = new ProfileChecker($database);
        echo json_encode($checker->checkProfile($api_key), JSON_UNESCAPED_UNICODE);
        exit();
    } elseif ($_SERVER['REQUEST_METHOD'] === 'GET') {
        $api_key = $_GET['api_key'] ?? null;
        $user_id = $_GET['user_id'] ?? null;
        $database = new Database();
        $checker = new ProfileChecker($database);
        echo json_encode($checker->checkProfile($api_key, $user_id), JSON_UNESCAPED_UNICODE);
        exit();
    } else {
        echo json_encode(['success' => false, 'error' => 'Неподдерживаемый метод', 'code' => 'INVALID_METHOD']);
        exit();
    }
}

