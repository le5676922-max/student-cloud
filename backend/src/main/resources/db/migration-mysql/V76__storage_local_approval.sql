CREATE TABLE storage_local_request (
    id VARCHAR(32) NOT NULL PRIMARY KEY,
    tenant_id INT NOT NULL,
    requested_by INT NOT NULL,
    reason VARCHAR(500) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    storage_id INT NULL,
    reviewed_by INT NULL,
    review_note VARCHAR(500) NOT NULL DEFAULT '',
    created_at BIGINT NOT NULL,
    reviewed_at BIGINT NULL,
    INDEX idx_local_request_tenant_status (tenant_id, status, created_at),
    INDEX idx_local_request_status_created (status, created_at),
    FOREIGN KEY (tenant_id) REFERENCES tenant(id) ON DELETE CASCADE,
    FOREIGN KEY (requested_by) REFERENCES `user`(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
