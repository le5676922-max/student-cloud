-- Explicitly saved preferences only. The per-owner document is bounded and versioned by the service.
CREATE TABLE ai_long_term_memory (
    owner_user_id INT NOT NULL PRIMARY KEY,
    enabled TINYINT NOT NULL DEFAULT 1,
    entries_json MEDIUMTEXT NOT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_ai_long_term_memory_user FOREIGN KEY (owner_user_id) REFERENCES `user` (id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
