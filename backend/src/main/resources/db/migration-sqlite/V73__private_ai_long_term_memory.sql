CREATE TABLE ai_long_term_memory (
    owner_user_id INTEGER NOT NULL PRIMARY KEY,
    enabled INTEGER NOT NULL DEFAULT 1,
    entries_json TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 0,
    updated_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (owner_user_id) REFERENCES `user` (id) ON DELETE CASCADE
);
