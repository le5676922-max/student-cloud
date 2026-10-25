CREATE TABLE support_conversation (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    owner_user_id INT NOT NULL,
    active_owner_id INT NULL,
    tenant_id INT NULL,
    subject VARCHAR(100) NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT uq_support_active_owner UNIQUE (active_owner_id),
    CONSTRAINT fk_support_owner FOREIGN KEY (owner_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_support_owner_updated (owner_user_id, updated_at, id),
    INDEX idx_support_status_updated (status, updated_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE support_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(32) NOT NULL,
    sender_user_id INT NOT NULL,
    sender_role VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    client_message_id VARCHAR(80) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT uq_support_sender_client UNIQUE (sender_user_id, client_message_id),
    CONSTRAINT fk_support_message_conversation FOREIGN KEY (conversation_id) REFERENCES support_conversation (id) ON DELETE CASCADE,
    CONSTRAINT fk_support_message_sender FOREIGN KEY (sender_user_id) REFERENCES `user` (id) ON DELETE CASCADE,
    INDEX idx_support_message_history (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
