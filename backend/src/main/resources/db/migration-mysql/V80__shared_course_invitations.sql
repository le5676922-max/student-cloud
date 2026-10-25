CREATE TABLE shared_course_invitation (
    id VARCHAR(32) PRIMARY KEY,
    course_id VARCHAR(32) NOT NULL,
    code_hash CHAR(64) NOT NULL UNIQUE,
    created_at BIGINT NOT NULL,
    expires_at BIGINT NULL,
    revoked_at BIGINT NOT NULL DEFAULT 0,
    used_count BIGINT NOT NULL DEFAULT 0,
    max_members INT NULL,
    version BIGINT NOT NULL DEFAULT 0,
    INDEX idx_shared_course_invitation_course(course_id, revoked_at, expires_at),
    CONSTRAINT fk_shared_course_invitation FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE shared_course_invitation_use (
    invitation_id VARCHAR(32) NOT NULL,
    user_id INT NOT NULL,
    joined_at BIGINT NOT NULL,
    PRIMARY KEY(invitation_id,user_id),
    CONSTRAINT fk_shared_course_invitation_use FOREIGN KEY(invitation_id) REFERENCES shared_course_invitation(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
