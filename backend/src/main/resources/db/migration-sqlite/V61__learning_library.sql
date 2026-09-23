CREATE TABLE learning_course (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL,
    name VARCHAR(100) NOT NULL,
    semester VARCHAR(40) NOT NULL
);
CREATE INDEX idx_learning_course_owner ON learning_course(owner_user_id);
CREATE TABLE learning_material (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    owner_user_id INTEGER NOT NULL,
    course_id INTEGER NOT NULL,
    storage_key VARCHAR(100) NOT NULL,
    path VARCHAR(500) NOT NULL,
    name VARCHAR(255) NOT NULL,
    tags VARCHAR(200) NOT NULL,
    content TEXT NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    indexed_at DATETIME NOT NULL,
    UNIQUE(owner_user_id, course_id, storage_key, path)
);
CREATE INDEX idx_learning_material_owner ON learning_material(owner_user_id);
