-- Intelligence-owned projection metadata only. No content-service cross-database reads.
CREATE TABLE IF NOT EXISTS sc_document_projection (
    file_id VARCHAR(36) PRIMARY KEY,
    file_version_id VARCHAR(36) NOT NULL,
    scope VARCHAR(16) NOT NULL,
    owner_id BIGINT NOT NULL,
    acl_revision BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    source_hash CHAR(64) NOT NULL,
    parser_version VARCHAR(64),
    generation BIGINT NOT NULL DEFAULT 0,
    total_pages INT NOT NULL DEFAULT 0,
    covered_pages INT NOT NULL DEFAULT 0,
    updated_at BIGINT NOT NULL
);
CREATE TABLE IF NOT EXISTS sc_document_consumed_event (
    consumer VARCHAR(48) NOT NULL,
    event_id VARCHAR(128) NOT NULL,
    file_id VARCHAR(36) NOT NULL,
    sequence_no BIGINT NOT NULL,
    processed_at BIGINT NOT NULL,
    PRIMARY KEY (consumer,event_id)
);
