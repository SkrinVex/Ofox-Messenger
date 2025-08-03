<?php
require_once "config.php";
ini_set('display_errors', 1);
ini_set('display_startup_errors', 1);
error_reporting(E_ALL);

$showModal = false;
$message = "";
$userEmail = "";

if ($_SERVER["REQUEST_METHOD"] == "POST") {
    $email = filter_var($_POST['email'], FILTER_SANITIZE_EMAIL);
    $password = $_POST['password'];

    if (!filter_var($email, FILTER_VALIDATE_EMAIL) || empty($password)) {
        $message = "Неверный email или пароль.";
    } else {
        $db = new Database();
        $conn = $db->getConnection();

        $stmt = $conn->prepare("SELECT id, password_hash, email_verified FROM users WHERE email = ?");
        $stmt->execute([$email]);
        $user = $stmt->fetch(PDO::FETCH_ASSOC);

        if (!$user || !password_verify($password, $user['password_hash'])) {
            $message = "Неверный email или пароль.";
        } elseif ($user['email_verified'] == 0) {
            $message = "Email не подтвержден.";
        } else {
            $showModal = true;
            $userEmail = $email;
        }
    }
}
?>
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <title>Вход в Ofox</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="register-container">
        <div class="register-box">
            <h2>Вход</h2>
            <?php if (!empty($message)): ?>
                <p style="color: red; margin-bottom: 10px;"><?= htmlspecialchars($message) ?></p>
            <?php endif; ?>
            <form method="POST">
                <input type="email" name="email" placeholder="Email" required>
                <input type="password" name="password" placeholder="Пароль" required>
                <button type="submit">Войти</button>
            </form>
        </div>
    </div>

    <?php if ($showModal): ?>
        <div id="successModal" class="modal" style="display:flex;">
            <div class="modal-content">
                <span class="close-modal" onclick="document.getElementById('successModal').style.display='none';">&times;</span>
                <h2>Добро пожаловать!</h2>
                <p>Вы вошли как <b><?= htmlspecialchars($userEmail) ?></b>.</p>
                <button onclick="document.getElementById('successModal').style.display='none';">OK</button>
            </div>
        </div>
    <?php endif; ?>
</body>
</html>
