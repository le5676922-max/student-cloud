CREATE TABLE shared_course_invitation (
    id TEXT PRIMARY KEY,
    course_id TEXT NOT NULL,
    code_hash TEXT NOT NULL UNIQUE,
    created_at INTEGER NOT NULL,
    expires_at INTEGER NULL,
    revoked_at INTEGER NOT NULL DEFAULT 0,
    used_count INTEGER NOT NULL DEFAULT 0,
    max_members INTEGER NULL,
    version INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
);
CREATE INDEX idx_shared_course_invitation_course ON shared_course_invitation(course_id,revoked_at,expires_at);
CREATE TABLE shared_course_invitation_use (
    invitation_id TEXT NOT NULL,
    user_id INTEGER NOT NULL,
    joined_at INTEGER NOT NULL,
    PRIMARY KEY(invitation_id,user_id),
    FOREIGN KEY(invitation_id) REFERENCES shared_course_invitation(id) ON DELETE CASCADE
);
