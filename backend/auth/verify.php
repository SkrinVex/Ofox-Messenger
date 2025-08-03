<?php
require_once "config.php";
$message = "";

if (isset($_GET['token'])) {
    $token = $_GET['token'];
    $db = new Database();
    $conn = $db->getConnection();

    $stmt = $conn->prepare("SELECT id, email_verified FROM users WHERE api_key = ?");
    $stmt->execute([$token]);
    $user = $stmt->fetch(PDO::FETCH_ASSOC);

    if ($user) {
        if ($user['email_verified'] == 0) {
            $stmt = $conn->prepare("UPDATE users SET email_verified = 1, api_key = NULL WHERE id = ?");
            $stmt->execute([$user['id']]);
            $message = "Ваш email подтверждён! Теперь вы можете войти.";
        } else {
            $message = "Этот email уже был подтверждён.";
        }
    } else {
        $message = "Неверная или уже использованная ссылка.";
    }
} else {
    $message = "Нет токена для подтверждения.";
}
?>
<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Подтверждение Email</title>
    <style>
        body {
            margin: 0;
            font-family: Arial, sans-serif;
            background-color: #101010;
            color: #fff;
            display: flex;
            justify-content: center;
            align-items: center;
            min-height: 100vh;
            padding: 16px;
        }

        .modal-content {
            background: #1e1e1e;
            padding: 20px;
            border-radius: 16px;
            max-width: 400px;
            width: 100%;
            text-align: center;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
        }

        h2 {
            margin-bottom: 12px;
        }

        p {
            margin-bottom: 20px;
        }

        button {
            background: #FF6B35;
            border: none;
            color: white;
            padding: 12px;
            border-radius: 8px;
            cursor: pointer;
            width: 100%;
            font-size: 16px;
        }

        button:hover {
            background: #e85b2a;
        }
    </style>
</head>
<body>
    <div class="modal-content">
        <h2>Подтверждение Email</h2>
        <p><?= htmlspecialchars($message) ?></p>
        <button onclick="window.location.href='index.php';">OK</button>
    </div>
</body>
</html>
