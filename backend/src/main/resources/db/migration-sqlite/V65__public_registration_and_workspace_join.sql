ALTER TABLE user ADD COLUMN account_id VARCHAR(32) NULL;
ALTER TABLE user ADD COLUMN email VARCHAR(254) NULL;
UPDATE user SET account_id = 'SC' || printf('%012d', id) WHERE account_id IS NULL;
CREATE UNIQUE INDEX uk_user_account_id ON user (account_id);
CREATE UNIQUE INDEX uk_user_email ON user (email);

CREATE TABLE workspace_invitation (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id INTEGER NOT NULL,
    issuer_user_id INTEGER NOT NULL,
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    max_uses INTEGER NOT NULL,
    use_count INTEGER NOT NULL DEFAULT 0,
    revoked BOOLEAN NOT NULL DEFAULT 0
);
CREATE INDEX ix_workspace_invitation_tenant ON workspace_invitation (tenant_id);

CREATE TABLE workspace_join_request (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    tenant_id INTEGER NOT NULL,
    user_id INTEGER NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    reviewer_user_id INTEGER NULL
);
CREATE INDEX ix_workspace_join_tenant_status ON workspace_join_request (tenant_id, status);
