<!DOCTYPE html>
<html lang="ru">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>Регистрация в Ofox</title>
    <link rel="stylesheet" href="style.css">
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

        .register-container {
            width: 100%;
            max-width: 400px;
        }

        .register-box {
            background: #1e1e1e;
            padding: 20px;
            border-radius: 16px;
            box-shadow: 0 4px 12px rgba(0, 0, 0, 0.3);
            text-align: center;
        }

        .logo {
            width: 80px;
            margin-bottom: 16px;
        }

        h2 {
            margin-bottom: 16px;
            font-size: 20px;
        }

        form input {
            width: 100%;
            padding: 14px;
            margin-bottom: 12px;
            border: none;
            border-radius: 8px;
            background: #2a2a2a;
            color: #fff;
            font-size: 16px;
        }

        .password-strength {
            height: 6px;
            border-radius: 5px;
            margin-bottom: 12px;
            background: #ccc;
            overflow: hidden;
        }

        .password-strength-bar {
            height: 100%;
            width: 0%;
            transition: width 0.3s, background-color 0.3s;
        }

        button {
            background: #FF6B35;
            border: none;
            color: white;
            padding: 14px;
            width: 100%;
            border-radius: 12px;
            font-size: 16px;
            cursor: pointer;
        }

        button:hover {
            background: #e85b2a;
        }
    </style>
</head>
<body>
    <div class="register-container">
        <div class="register-box">
            <img src="app_icon.png" alt="Ofox" class="logo">
            <h2>Регистрация в Ofox</h2>
            <form action="register.php" method="post">
                <input type="email" name="email" placeholder="Email" required>
                
                <input type="password" id="password" name="password" placeholder="Пароль" required>
                <div class="password-strength">
                    <div id="password-strength-bar" class="password-strength-bar"></div>
                </div>
                
                <input type="password" name="confirm_password" placeholder="Повторите пароль" required>
                <button type="submit">Зарегистрироваться</button>
            </form>
        </div>
    </div>

    <script>
        const passwordInput = document.getElementById('password');
        const strengthBar = document.getElementById('password-strength-bar');

        passwordInput.addEventListener('input', () => {
            const value = passwordInput.value;
            let strength = 0;

            if (value.length >= 8) strength += 1;
            if (/[A-Z]/.test(value)) strength += 1;
            if (/[0-9]/.test(value)) strength += 1;
            if (/[^A-Za-z0-9]/.test(value)) strength += 1;

            const percent = (strength / 4) * 100;
            strengthBar.style.width = percent + '%';

            if (percent <= 25) strengthBar.style.backgroundColor = 'red';
            else if (percent <= 50) strengthBar.style.backgroundColor = 'orange';
            else if (percent <= 75) strengthBar.style.backgroundColor = '#f1c40f';
            else strengthBar.style.backgroundColor = 'green';
        });
    </script>
</body>
</html>
