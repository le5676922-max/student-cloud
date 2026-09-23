CREATE TABLE learning_course (
    id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_user_id INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    semester VARCHAR(40) NOT NULL,
    INDEX idx_learning_course_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE learning_material (
    id INTEGER NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_user_id INTEGER NOT NULL,
    course_id INTEGER NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    path VARCHAR(500) NOT NULL,
    name VARCHAR(255) NOT NULL,
    tags VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    indexed_at DATETIME NOT NULL,
    UNIQUE KEY uk_learning_material_path (owner_user_id, course_id, storage_key, path),
    INDEX idx_learning_material_owner (owner_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
