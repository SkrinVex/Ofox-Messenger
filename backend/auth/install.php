<?php
require_once "config.php";

$db = new Database();
$conn = $db->getConnection();

/**
 * Проверяет наличие таблицы
 */
function tableExists($conn, $table) {
    $stmt = $conn->prepare("SHOW TABLES LIKE :table");
    $stmt->execute([':table' => $table]);
    return $stmt->rowCount() > 0;
}

/**
 * Возвращает список существующих столбцов таблицы
 */
function getTableColumns($conn, $table) {
    $stmt = $conn->prepare("SHOW COLUMNS FROM `$table`");
    $stmt->execute();
    return array_column($stmt->fetchAll(PDO::FETCH_ASSOC), 'Field');
}

/**
 * Создаёт таблицу или добавляет недостающие столбцы
 */
function ensureTable($conn, $table, $columns, $createSQL) {
    if (!tableExists($conn, $table)) {
        $conn->exec($createSQL);
        echo "Таблица `$table` создана.<br>";
    } else {
        $existing = getTableColumns($conn, $table);
        foreach ($columns as $col => $definition) {
            if (!in_array($col, $existing)) {
                $conn->exec("ALTER TABLE `$table` ADD COLUMN $col $definition");
                echo "Добавлен столбец `$col` в таблицу `$table`.<br>";
            }
        }
    }
}

// ---------- USERS ----------
ensureTable($conn, 'users', [
    'id' => 'INT AUTO_INCREMENT PRIMARY KEY',
    'email' => 'VARCHAR(255) NOT NULL UNIQUE',
    'password' => 'VARCHAR(255) NOT NULL',
    'token' => 'VARCHAR(64)',
    'verified' => 'TINYINT(1) DEFAULT 0',
    'created_at' => 'TIMESTAMP DEFAULT CURRENT_TIMESTAMP'
], "
CREATE TABLE users (
    id INT AUTO_INCREMENT PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    token VARCHAR(64),
    verified TINYINT(1) DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
");

// ---------- FRIEND_REQUESTS ----------
ensureTable($conn, 'friend_requests', [
    'id' => 'INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY',
    'sender_id' => 'INT(11) NOT NULL',
    'receiver_id' => 'INT(11) NOT NULL',
    'status' => "ENUM('pending','accepted','declined','removed') NOT NULL DEFAULT 'pending'",
    'created_at' => 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP',
    'updated_at' => 'TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP'
], "
CREATE TABLE friend_requests (
    id INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    sender_id INT(11) NOT NULL,
    receiver_id INT(11) NOT NULL,
    status ENUM('pending','accepted','declined','removed') NOT NULL DEFAULT 'pending',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NULL DEFAULT NULL ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY unique_request (sender_id, receiver_id),
    KEY idx_sender (sender_id),
    KEY idx_receiver (receiver_id),
    KEY idx_status (status),
    FOREIGN KEY (sender_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (receiver_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
");

// ---------- FRIENDS ----------
ensureTable($conn, 'friends', [
    'id' => 'INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY',
    'user_id' => 'INT(11) NOT NULL',
    'friend_id' => 'INT(11) NOT NULL',
    'created_at' => 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP'
], "
CREATE TABLE friends (
    id INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT(11) NOT NULL,
    friend_id INT(11) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY unique_friendship (user_id, friend_id),
    KEY idx_user (user_id),
    KEY idx_friend (friend_id),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    FOREIGN KEY (friend_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
");

// ---------- NOTIFICATIONS ----------
ensureTable($conn, 'notifications', [
    'id' => 'INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY',
    'user_id' => 'INT(11) NOT NULL',
    'title' => 'VARCHAR(255) NOT NULL',
    'content' => 'TEXT NOT NULL',
    'type' => 'VARCHAR(50) NOT NULL',
    'is_read' => 'TINYINT(1) NOT NULL DEFAULT 0',
    'created_at' => 'TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP'
], "
CREATE TABLE notifications (
    id INT(11) NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id INT(11) NOT NULL,
    title VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(50) NOT NULL,
    is_read TINYINT(1) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_user (user_id),
    KEY idx_type (type),
    KEY idx_read (is_read),
    FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
");

// ---------- Индексы ----------
$conn->exec("CREATE INDEX IF NOT EXISTS idx_friend_requests_pending ON friend_requests (status, created_at)");
$conn->exec("CREATE INDEX IF NOT EXISTS idx_notifications_unread ON notifications (user_id, is_read, created_at)");

echo "Проверка и обновление структуры базы данных завершены.";
