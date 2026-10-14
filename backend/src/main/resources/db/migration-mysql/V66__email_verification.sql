CREATE TABLE email_verification (
    id VARCHAR(64) PRIMARY KEY,
    email VARCHAR(254) NOT NULL,
    purpose VARCHAR(16) NOT NULL,
    code_digest VARCHAR(64) NOT NULL,
    nonce VARCHAR(32) NOT NULL,
    expires_at BIGINT NOT NULL,
    resend_at BIGINT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    consumed BOOLEAN NOT NULL DEFAULT FALSE,
    updated_at BIGINT NOT NULL,
    INDEX ix_email_verification_expiry (expires_at)
);
