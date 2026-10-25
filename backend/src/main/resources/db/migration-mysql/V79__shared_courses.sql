CREATE TABLE shared_course (
    id VARCHAR(32) PRIMARY KEY,
    owner_user_id INT NOT NULL,
    tenant_id INT NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    code_hash CHAR(64) NOT NULL UNIQUE,
    enabled INT NOT NULL DEFAULT 1,
    quota_bytes BIGINT NOT NULL,
    used_bytes BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    created_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    INDEX idx_shared_course_owner(owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE shared_course_member (
    course_id VARCHAR(32) NOT NULL,
    user_id INT NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'JOINED',
    upload_status VARCHAR(16) NOT NULL DEFAULT 'NONE',
    joined_at BIGINT NOT NULL,
    updated_at BIGINT NOT NULL,
    PRIMARY KEY(course_id, user_id),
    INDEX idx_shared_course_member_user(user_id, status),
    CONSTRAINT fk_shared_course_member FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE shared_course_material (
    id VARCHAR(32) PRIMARY KEY,
    course_id VARCHAR(32) NOT NULL,
    uploader_user_id INT NOT NULL,
    filename VARCHAR(240) NOT NULL,
    size_bytes BIGINT NOT NULL,
    state VARCHAR(16) NOT NULL,
    created_at BIGINT NOT NULL,
    INDEX idx_shared_course_material_course(course_id, state, created_at),
    CONSTRAINT fk_shared_course_material FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE shared_course_storage_usage (
    id INT PRIMARY KEY,
    used_bytes BIGINT NOT NULL DEFAULT 0
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO shared_course_storage_usage(id,used_bytes) VALUES(1,0);
