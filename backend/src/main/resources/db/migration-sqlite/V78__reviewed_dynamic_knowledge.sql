ALTER TABLE ai_answer_feedback ADD COLUMN course_id TEXT NULL;

CREATE TABLE ai_curated_knowledge (
    feedback_id TEXT NOT NULL PRIMARY KEY,
    version INTEGER NOT NULL,
    state TEXT NOT NULL,
    updated_at INTEGER NOT NULL,
    FOREIGN KEY (feedback_id) REFERENCES ai_answer_feedback(id) ON DELETE CASCADE
);
CREATE TABLE ai_curated_knowledge_revision (
    feedback_id TEXT NOT NULL,
    version INTEGER NOT NULL,
    scope TEXT NOT NULL,
    tenant_id INTEGER NULL,
    course_id TEXT NULL,
    title TEXT NOT NULL,
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    tags TEXT NOT NULL,
    source_updated_at INTEGER NOT NULL,
    published_by INTEGER NULL,
    published_at INTEGER NOT NULL,
    PRIMARY KEY (feedback_id, version),
    FOREIGN KEY (feedback_id) REFERENCES ai_curated_knowledge(feedback_id) ON DELETE CASCADE,
    FOREIGN KEY (published_by) REFERENCES `user`(id) ON DELETE SET NULL
);
CREATE INDEX idx_curated_scope_tenant ON ai_curated_knowledge_revision(scope,tenant_id);
CREATE INDEX idx_curated_scope_course ON ai_curated_knowledge_revision(scope,course_id);
