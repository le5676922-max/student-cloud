ALTER TABLE user_storage_source ADD COLUMN quota_bytes BIGINT NOT NULL DEFAULT 5368709120;
ALTER TABLE user_storage_source ADD COLUMN quota_revision BIGINT NOT NULL DEFAULT 0;
UPDATE user_storage_source SET enable=0 WHERE user_id IN (SELECT id FROM user WHERE role='USER')
AND NOT (root_path LIKE ('%/members/u' || user_id) OR root_path LIKE ('%/members/u' || user_id || '/%'));
CREATE TABLE workspace_cloud_request (
    id VARCHAR(32) PRIMARY KEY,
    user_id INT NOT NULL REFERENCES user(id) ON DELETE CASCADE,
    tenant_id INT NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    storage_id INT NOT NULL REFERENCES storage_source(id) ON DELETE CASCADE,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    reason VARCHAR(500) NOT NULL DEFAULT '',
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    reviewed_by INT,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    UNIQUE(user_id,storage_id)
);
CREATE INDEX idx_cloud_request_tenant_status ON workspace_cloud_request(tenant_id,status,created_at);
