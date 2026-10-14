ALTER TABLE user
    ADD COLUMN account_id VARCHAR(32) NULL COMMENT '不可变系统账号',
    ADD COLUMN email VARCHAR(254) NULL COMMENT '已验证的规范化邮箱',
    MODIFY COLUMN password VARCHAR(255) NULL,
    MODIFY COLUMN salt VARCHAR(128) NULL;

UPDATE user SET account_id = CONCAT('SC', LPAD(CAST(id AS CHAR), 12, '0')) WHERE account_id IS NULL;
ALTER TABLE user MODIFY COLUMN account_id VARCHAR(32) NOT NULL;
CREATE UNIQUE INDEX uk_user_account_id ON user (account_id);
CREATE UNIQUE INDEX uk_user_email ON user (email);

CREATE TABLE workspace_invitation (
    id INT AUTO_INCREMENT PRIMARY KEY,
    tenant_id INT NOT NULL,
    issuer_user_id INT NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL,
    max_uses INT NOT NULL,
    use_count INT NOT NULL DEFAULT 0,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT uk_workspace_invitation_hash UNIQUE (token_hash),
    INDEX ix_workspace_invitation_tenant (tenant_id)
);

CREATE TABLE workspace_join_request (
    id INT AUTO_INCREMENT PRIMARY KEY,
    tenant_id INT NOT NULL,
    user_id INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    created_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    reviewer_user_id INT NULL,
    CONSTRAINT uk_workspace_join_user UNIQUE (user_id),
    INDEX ix_workspace_join_tenant_status (tenant_id, status)
);
