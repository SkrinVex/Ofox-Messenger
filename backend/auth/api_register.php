<?php
require_once "config.php";
header('Content-Type: application/json; charset=UTF-8');
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

/*
 * === Ожидаемый запрос ===
 * Метод: POST
 * Параметры: email, password, confirm_password
 */

if ($_SERVER["REQUEST_METHOD"] !== "POST") {
    echo json_encode(["status" => "error", "message" => "Метод не поддерживается"]);
    exit;
}

$email = filter_var($_POST['email'] ?? '', FILTER_SANITIZE_EMAIL);
$password = $_POST['password'] ?? '';
$confirm_password = $_POST['confirm_password'] ?? '';

if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
    echo json_encode(["status" => "error", "message" => "Неверный формат email"]);
    exit;
}

if ($password !== $confirm_password) {
    echo json_encode(["status" => "error", "message" => "Пароли не совпадают"]);
    exit;
}

try {
    $db = new Database();
    $conn = $db->getConnection();

    // Проверяем, существует ли email
    $stmt = $conn->prepare("SELECT id FROM users WHERE email = ?");
    $stmt->execute([$email]);
    if ($stmt->fetch()) {
        echo json_encode(["status" => "error", "message" => "Этот email уже зарегистрирован"]);
        exit;
    }

    $hashed_password = password_hash($password, PASSWORD_BCRYPT);
    $token = bin2hex(random_bytes(32));

    // Добавляем пользователя
    $stmt = $conn->prepare("INSERT INTO users (email, password_hash, api_key, email_verified) VALUES (?, ?, ?, 0)");
    $stmt->execute([$email, $hashed_password, $token]);

    // Отправка письма
    $verify_link = "https://api.greenchat.kz/auth/verify.php?token=$token";
    $subject = "Подтверждение регистрации в Ofox";
    $message = "
    <html>
    <head>
      <style>
        body { font-family: Arial, sans-serif; background-color: #121212; color: #ffffff; }
        .container { max-width: 600px; margin: 50px auto; background: #1e1e1e; padding: 20px; border-radius: 10px; box-shadow: 0 4px 15px rgba(0,0,0,0.3); }
        h2 { color: #ff9800; }
        .btn { display: inline-block; padding: 12px 25px; margin-top: 20px; background-color: #ff9800; color: #121212; text-decoration: none; font-weight: bold; border-radius: 5px; }
        .footer { margin-top: 30px; font-size: 12px; color: #bbbbbb; text-align: center; }
      </style>
    </head>
    <body>
      <div class='container'>
        <h2>Подтверждение регистрации Ofox Auth</h2>
        <p>Здравствуйте! Спасибо за регистрацию. Подтвердите ваш email:</p>
        <a href='$verify_link' class='btn'>Подтвердить Email</a>
        <div class='footer'>© 2025 SkrinVex. Все права защищены.</div>
      </div>
    </body>
    </html>";
    $headers = "From: Ofox Auth <no-reply@greenchat.kz>\r\n";
    $headers .= "MIME-Version: 1.0\r\n";
    $headers .= "Content-type: text/html; charset=UTF-8\r\n";

    mail($email, $subject, $message, $headers);

    echo json_encode(["status" => "success", "message" => "Регистрация успешна! Проверьте почту."]);
} catch (PDOException $e) {
    echo json_encode(["status" => "error", "message" => "Ошибка базы данных"]);
}
