CREATE TABLE resumable_upload_quota (
    id INTEGER PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 0
);
INSERT INTO resumable_upload_quota (id, revision) VALUES (1, 0);
CREATE TABLE resumable_upload_session (
    id VARCHAR(36) PRIMARY KEY,
    actor_id INTEGER NOT NULL,
    storage_id INTEGER NOT NULL,
    storage_key VARCHAR(128) NOT NULL,
    grant_root VARCHAR(1000) NOT NULL,
    root_fingerprint VARCHAR(64) NOT NULL,
    staging_fingerprint VARCHAR(64) NOT NULL,
    directory_path VARCHAR(1000) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    total_size BIGINT NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    chunk_hashes TEXT NOT NULL,
    received_mask VARCHAR(128) NOT NULL DEFAULT '',
    resume_key VARCHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    reserved_bytes BIGINT NOT NULL,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    expires_at BIGINT NOT NULL
);
CREATE INDEX idx_resumable_upload_resume ON resumable_upload_session(actor_id, resume_key, expires_at);
CREATE INDEX idx_resumable_upload_expiry ON resumable_upload_session(expires_at, reserved_bytes);
CREATE INDEX idx_resumable_upload_actor ON resumable_upload_session(actor_id, state);
