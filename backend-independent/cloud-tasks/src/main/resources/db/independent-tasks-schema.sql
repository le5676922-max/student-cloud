-- Independent task storage; never executed against the legacy database.
-- Epoch milliseconds keep the lease contract identical on MySQL 8 and H2 MODE=MySQL.
CREATE TABLE sc_job (
    job_id VARCHAR(36) PRIMARY KEY,
    actor_id BIGINT NOT NULL,
    workspace_id BIGINT NOT NULL,
    task_type VARCHAR(32) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    payload_digest CHAR(64) NOT NULL,
    idempotency_hash CHAR(64) NOT NULL,
    state VARCHAR(16) NOT NULL,
    attempt_count INT NOT NULL,
    max_attempts INT NOT NULL,
    available_at BIGINT NOT NULL,
    worker_id VARCHAR(96),
    lease_until BIGINT,
    fence BIGINT NOT NULL,
    result_payload MEDIUMTEXT,
    failure_code VARCHAR(64),
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    finished_at BIGINT,
    CONSTRAINT uq_sc_job_idempotency UNIQUE (idempotency_hash)
);
CREATE INDEX ix_sc_job_claim ON sc_job (state, available_at, lease_until);
CREATE INDEX ix_sc_job_owner ON sc_job (actor_id, created_at, job_id);

CREATE TABLE sc_job_outbox (
    event_id VARCHAR(36) PRIMARY KEY,
    job_id VARCHAR(36) NOT NULL,
    event_type VARCHAR(32) NOT NULL,
    event_payload MEDIUMTEXT NOT NULL,
    created_at BIGINT NOT NULL,
    published_at BIGINT,
    CONSTRAINT uq_sc_job_created_event UNIQUE (job_id, event_type),
    CONSTRAINT fk_sc_job_outbox_job FOREIGN KEY (job_id) REFERENCES sc_job(job_id)
);
CREATE INDEX ix_sc_job_outbox_delivery ON sc_job_outbox (published_at, created_at, event_id);
