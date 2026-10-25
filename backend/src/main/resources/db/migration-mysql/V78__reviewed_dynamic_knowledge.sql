ALTER TABLE ai_answer_feedback ADD COLUMN course_id VARCHAR(32) NULL;

CREATE TABLE ai_curated_knowledge (
    feedback_id VARCHAR(32) NOT NULL PRIMARY KEY,
    version INT NOT NULL,
    state VARCHAR(16) NOT NULL,
    updated_at BIGINT NOT NULL,
    CONSTRAINT fk_curated_feedback FOREIGN KEY (feedback_id) REFERENCES ai_answer_feedback(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE ai_curated_knowledge_revision (
    feedback_id VARCHAR(32) NOT NULL,
    version INT NOT NULL,
    scope VARCHAR(16) NOT NULL,
    tenant_id INT NULL,
    course_id VARCHAR(32) NULL,
    title VARCHAR(200) NOT NULL,
    question TEXT NOT NULL,
    answer MEDIUMTEXT NOT NULL,
    tags VARCHAR(1000) NOT NULL,
    source_updated_at BIGINT NOT NULL,
    published_by INT NULL,
    published_at BIGINT NOT NULL,
    PRIMARY KEY (feedback_id, version),
    CONSTRAINT fk_curated_revision_entry FOREIGN KEY (feedback_id) REFERENCES ai_curated_knowledge(feedback_id) ON DELETE CASCADE,
    CONSTRAINT fk_curated_revision_publisher FOREIGN KEY (published_by) REFERENCES `user`(id) ON DELETE SET NULL,
    INDEX idx_curated_scope_tenant (scope, tenant_id),
    INDEX idx_curated_scope_course (scope, course_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
