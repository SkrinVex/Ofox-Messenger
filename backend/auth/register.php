<?php
require_once "config.php";
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

$showModal = false;

if ($_SERVER["REQUEST_METHOD"] == "POST") {
    $email = filter_var($_POST['email'], FILTER_SANITIZE_EMAIL);
    $password = $_POST['password'];
    $confirm_password = $_POST['confirm_password'];

    if (!filter_var($email, FILTER_VALIDATE_EMAIL)) {
        die("Неверный формат email");
    }
    if ($password !== $confirm_password) {
        die("Пароли не совпадают");
    }

    $db = new Database();
    $conn = $db->getConnection();

    // Проверяем, есть ли колонка nickname, и создаем при необходимости
    $result = $conn->query("SHOW COLUMNS FROM users LIKE 'nickname'");
    if ($result->rowCount() == 0) {
        $conn->exec("ALTER TABLE users ADD COLUMN nickname VARCHAR(20) UNIQUE NOT NULL");
    }

    // Проверка существующего email
    $stmt = $conn->prepare("SELECT id FROM users WHERE email = ?");
    $stmt->execute([$email]);
    if ($stmt->fetch()) {
        die("Этот email уже зарегистрирован");
    }

    // Генерируем уникальный никнейм
    do {
        $nickname = 'user-' . random_int(100000, 999999);
        $checkNick = $conn->prepare("SELECT id FROM users WHERE nickname = ?");
        $checkNick->execute([$nickname]);
    } while ($checkNick->fetch());

    $hashed_password = password_hash($password, PASSWORD_BCRYPT);
    $token = bin2hex(random_bytes(32));

    $stmt = $conn->prepare("INSERT INTO users (nickname, email, password_hash, api_key, email_verified) VALUES (?, ?, ?, ?, 0)");
    $stmt->execute([$nickname, $email, $hashed_password, $token]);

    // Письмо с подтверждением (HTML-версия)
	$verify_link = "https://api.greenchat.kz/auth/verify.php?token=$token";
	$subject = "Подтверждение регистрации в Ofox (#" . substr($token, 0, 6) . ")";
	$message = "
	<html>
	<head>
	  <style>
		body { font-family: Arial, sans-serif; background-color: #121212; color: #ffffff; }
		.container { max-width: 600px; margin: 50px auto; background: #1e1e1e; padding: 20px; border-radius: 10px; box-shadow: 0 4px 15px rgba(0,0,0,0.3); }
		h2 { color: #ff9800; }
		p { line-height: 1.5; }
		.btn { display: inline-block; padding: 12px 25px; margin-top: 20px; background-color: #ff9800; color: #121212; text-decoration: none; font-weight: bold; border-radius: 5px; }
		.btn:hover { background-color: #ffa733; }
		.footer { margin-top: 30px; font-size: 12px; color: #bbbbbb; text-align: center; }
	  </style>
	</head>
	<body>
	  <div class='container'>
		<h2>Подтверждение регистрации Ofox Auth</h2>
		<p>Здравствуйте! Спасибо за регистрацию. Для завершения процесса подтвердите ваш адрес электронной почты:</p>
		<a href='$verify_link' class='btn'>Подтвердить Email</a>
		<p style='margin-top:20px;'>Если кнопка не работает, скопируйте ссылку и вставьте её в адресную строку браузера:</p>
		<p><a href='$verify_link' style='color:#ff9800;'>$verify_link</a></p>
		<div class='footer'>© 2025 SkrinVex. Все права защищены.</div>
	  </div>
	</body>
	</html>
	";
	$headers = "From: Ofox Auth <no-reply@greenchat.kz>\r\n";
	$headers .= "Reply-To: Ofox Auth <no-reply@greenchat.kz>\r\n";
	$headers .= "Return-Path: no-reply@greenchat.kz\r\n";
	$headers .= "MIME-Version: 1.0\r\n";
	$headers .= "Content-type: text/html; charset=UTF-8\r\n";

	mail($email, $subject, $message, $headers);

    $showModal = true;
}
?>
<!DOCTYPE html>
<html lang="ru">
<head>
<meta charset="UTF-8">
<link rel="stylesheet" href="style.css">
</head>
<body>
<?php if ($showModal): ?>
<div id="successModal" class="modal" style="display:flex;">
  <div class="modal-content">
    <span class="close-modal" onclick="document.getElementById('successModal').style.display='none'; window.location.href='index.php';">&times;</span>
    <h2>Регистрация успешна!</h2>
    <p>Проверьте почту для подтверждения регистрации.</p>
    <button onclick="window.location.href='index.php';">OK</button>
  </div>
</div>
<?php endif; ?>
</body>
</html>
