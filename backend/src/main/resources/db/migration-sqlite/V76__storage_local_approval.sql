CREATE TABLE storage_local_request (
    id TEXT NOT NULL PRIMARY KEY,
    tenant_id INTEGER NOT NULL,
    requested_by INTEGER NOT NULL,
    reason TEXT NOT NULL,
    status TEXT NOT NULL DEFAULT 'PENDING',
    storage_id INTEGER NULL,
    reviewed_by INTEGER NULL,
    review_note TEXT NOT NULL DEFAULT '',
    created_at INTEGER NOT NULL,
    reviewed_at INTEGER NULL,
    FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
    FOREIGN KEY (requested_by) REFERENCES `user`(id) ON DELETE CASCADE
);
CREATE INDEX idx_local_request_tenant_status ON storage_local_request(tenant_id, status, created_at);
CREATE INDEX idx_local_request_status_created ON storage_local_request(status, created_at);
