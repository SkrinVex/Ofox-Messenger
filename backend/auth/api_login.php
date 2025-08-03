<?php
require_once "config.php";
header('Content-Type: application/json; charset=UTF-8');
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

if ($_SERVER["REQUEST_METHOD"] !== "POST") {
    echo json_encode(["status" => "error", "message" => "Метод не поддерживается"]);
    exit;
}

$email = filter_var($_POST['email'] ?? '', FILTER_SANITIZE_EMAIL);
$password = $_POST['password'] ?? '';

if (!filter_var($email, FILTER_VALIDATE_EMAIL) || empty($password)) {
    echo json_encode(["status" => "error", "message" => "Неверный email или пароль"]);
    exit;
}

$ip = $_SERVER['REMOTE_ADDR'];
$max_attempts = 5;
$lockout_minutes = 10;

function callProfileCheck($apiKey) {
    $url = 'https://api.greenchat.kz/ofox/profile_check.php';
    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_POST, true);
    curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json']);
    curl_setopt($ch, CURLOPT_POSTFIELDS, json_encode(['api_key' => $apiKey]));
    $response = curl_exec($ch);
    curl_close($ch);

    $data = json_decode($response, true);
    return is_array($data) ? $data : (object)[];
}

try {
    $db = new Database();
    $conn = $db->getConnection();

    $conn->exec("CREATE TABLE IF NOT EXISTS login_attempts (
        id INT AUTO_INCREMENT PRIMARY KEY,
        ip VARCHAR(45) NOT NULL,
        email VARCHAR(255),
        attempts INT NOT NULL DEFAULT 0,
        last_attempt TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    )");

    $stmt = $conn->prepare("SELECT attempts, UNIX_TIMESTAMP(last_attempt) as last_time FROM login_attempts WHERE ip = ?");
    $stmt->execute([$ip]);
    $attempt = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($attempt) {
        $time_since_last = time() - $attempt['last_time'];
        if ($attempt['attempts'] >= $max_attempts && $time_since_last < ($lockout_minutes * 60)) {
            echo json_encode(["status" => "error", "message" => "Слишком много попыток. Подождите {$lockout_minutes} минут."]);
            exit;
        }
    }

    $stmt = $conn->prepare("SELECT id, password_hash, email_verified, api_key FROM users WHERE email = ?");
    $stmt->execute([$email]);
    $user = $stmt->fetch(PDO::FETCH_ASSOC);

    if (!$user || !password_verify($password, $user['password_hash'])) {
        if ($attempt) {
            if ($time_since_last > ($lockout_minutes * 60)) {
                $stmt = $conn->prepare("UPDATE login_attempts SET attempts = 1, email = ?, last_attempt = NOW() WHERE ip = ?");
                $stmt->execute([$email, $ip]);
            } else {
                $stmt = $conn->prepare("UPDATE login_attempts SET attempts = attempts + 1, email = ?, last_attempt = NOW() WHERE ip = ?");
                $stmt->execute([$email, $ip]);
            }
        } else {
            $stmt = $conn->prepare("INSERT INTO login_attempts (ip, email, attempts) VALUES (?, ?, 1)");
            $stmt->execute([$ip, $email]);
        }
        echo json_encode(["status" => "error", "message" => "Неверный email или пароль"]);
        exit;
    }

    if ($user['email_verified'] == 0) {
        echo json_encode(["status" => "error", "message" => "Email не подтвержден"]);
        exit;
    }

    $stmt = $conn->prepare("DELETE FROM login_attempts WHERE ip = ?");
    $stmt->execute([$ip]);

    if (empty($user['api_key'])) {
        $user['api_key'] = bin2hex(random_bytes(32));
        $update = $conn->prepare("UPDATE users SET api_key = ? WHERE id = ?");
        $update->execute([$user['api_key'], $user['id']]);
    }

    $profile_check_json = callProfileCheck($user['api_key']);

    echo json_encode([
        "status" => "success",
        "message" => "Вход выполнен",
        "user_id" => $user['id'],
        "api_key" => $user['api_key'],
        "profile_check" => $profile_check_json
    ]);
} catch (PDOException $e) {
    echo json_encode(["status" => "error", "message" => "Ошибка базы данных"]);
}
