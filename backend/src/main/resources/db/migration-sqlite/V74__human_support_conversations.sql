CREATE TABLE support_conversation (
    id TEXT NOT NULL PRIMARY KEY,
    owner_user_id INTEGER NOT NULL,
    active_owner_id INTEGER NULL UNIQUE,
    tenant_id INTEGER NULL,
    subject TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (owner_user_id) REFERENCES `user` (id) ON DELETE CASCADE
);
CREATE INDEX idx_support_owner_updated ON support_conversation (owner_user_id, updated_at, id);
CREATE INDEX idx_support_status_updated ON support_conversation (status, updated_at, id);

CREATE TABLE support_message (
    id INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
    conversation_id TEXT NOT NULL,
    sender_user_id INTEGER NOT NULL,
    sender_role TEXT NOT NULL,
    content TEXT NOT NULL,
    client_message_id TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    UNIQUE (sender_user_id, client_message_id),
    FOREIGN KEY (conversation_id) REFERENCES support_conversation (id) ON DELETE CASCADE,
    FOREIGN KEY (sender_user_id) REFERENCES `user` (id) ON DELETE CASCADE
);
CREATE INDEX idx_support_message_history ON support_message (conversation_id, id);
