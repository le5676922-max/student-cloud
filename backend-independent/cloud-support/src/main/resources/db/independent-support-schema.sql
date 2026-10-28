-- Support data belongs to the new independent database only.
CREATE TABLE sc_support_conversation (
    conversation_id VARCHAR(36) NOT NULL PRIMARY KEY,
    requester_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    subject VARCHAR(160) NOT NULL,
    state VARCHAR(16) NOT NULL,
    assigned_admin_id BIGINT,
    last_message_no BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    closed_at BIGINT,
    closed_by BIGINT,
    CONSTRAINT fk_sc_support_requester FOREIGN KEY (requester_id) REFERENCES sc_account(id),
    CONSTRAINT fk_sc_support_workspace FOREIGN KEY (workspace_id) REFERENCES sc_workspace(id),
    CONSTRAINT fk_sc_support_administrator FOREIGN KEY (assigned_admin_id) REFERENCES sc_account(id),
    CONSTRAINT fk_sc_support_closed_by FOREIGN KEY (closed_by) REFERENCES sc_account(id)
);
CREATE INDEX ix_sc_support_owner ON sc_support_conversation (requester_id, created_at, conversation_id);
CREATE INDEX ix_sc_support_queue ON sc_support_conversation (state, updated_at, conversation_id);

CREATE TABLE sc_support_open_request (
    request_key CHAR(64) NOT NULL PRIMARY KEY,
    payload_hash CHAR(64) NOT NULL,
    conversation_id VARCHAR(36) NOT NULL,
    CONSTRAINT fk_sc_support_open_request FOREIGN KEY(conversation_id) REFERENCES sc_support_conversation(conversation_id)
);

CREATE TABLE sc_support_message (
    message_id VARCHAR(36) NOT NULL PRIMARY KEY,
    conversation_id VARCHAR(36) NOT NULL,
    message_no BIGINT NOT NULL,
    sender_id BIGINT NOT NULL,
    sender_role VARCHAR(20) NOT NULL,
    client_message_id VARCHAR(96) NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    body_digest CHAR(64) NOT NULL,
    body TEXT NOT NULL,
    created_at BIGINT NOT NULL,
    CONSTRAINT fk_sc_support_message_conversation FOREIGN KEY (conversation_id) REFERENCES sc_support_conversation(conversation_id),
    CONSTRAINT fk_sc_support_message_sender FOREIGN KEY (sender_id) REFERENCES sc_account(id),
    CONSTRAINT uq_sc_support_message_order UNIQUE (conversation_id, message_no),
    CONSTRAINT uq_sc_support_message_idempotency UNIQUE (conversation_id, idempotency_hash)
);
