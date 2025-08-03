<?php
class Database {
    private $host = "localhost:3306";
    private $db_name = "p-345278_ofox";
    private $username = "p-345278_Lexa";
    private $password = "Aleksey_Greb_2008";
    public $conn;

    public function getConnection() {
        $this->conn = null;
        try {
            $this->conn = new PDO("mysql:host=" . $this->host . ";dbname=" . $this->db_name . ";charset=utf8", $this->username, $this->password);
            $this->conn->setAttribute(PDO::ATTR_ERRMODE, PDO::ERRMODE_EXCEPTION);
        } catch(PDOException $e) {
            die("Ошибка соединения: " . $e->getMessage());
        }
        return $this->conn;
    }
}
?>
