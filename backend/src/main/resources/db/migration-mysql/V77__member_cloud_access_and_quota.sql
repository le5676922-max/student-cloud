ALTER TABLE user_storage_source ADD COLUMN quota_bytes BIGINT NOT NULL DEFAULT 5368709120;
ALTER TABLE user_storage_source ADD COLUMN quota_revision BIGINT NOT NULL DEFAULT 0;

-- Stop legacy shared-root grants exposing every member's files. Files remain untouched;
-- workspace administrators re-authorize each member's personal directory explicitly.
UPDATE user_storage_source g JOIN `user` u ON u.id=g.user_id
SET g.`enable`=0
WHERE u.role='USER' AND NOT (g.root_path LIKE CONCAT('%/members/u',u.id)
    OR g.root_path LIKE CONCAT('%/members/u',u.id,'/%'));

CREATE TABLE workspace_cloud_request (
    id VARCHAR(32) PRIMARY KEY,
    user_id INT NOT NULL,
    tenant_id INT NOT NULL,
    storage_id INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL DEFAULT '',
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    reviewed_by INT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE KEY uq_cloud_request_member_source (user_id,storage_id),
    INDEX idx_cloud_request_tenant_status (tenant_id,status,created_at),
    CONSTRAINT fk_cloud_request_user FOREIGN KEY(user_id) REFERENCES `user`(id) ON DELETE CASCADE,
    CONSTRAINT fk_cloud_request_storage FOREIGN KEY(storage_id) REFERENCES storage_source(id) ON DELETE CASCADE,
    CONSTRAINT fk_cloud_request_tenant FOREIGN KEY(tenant_id) REFERENCES tenant(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
