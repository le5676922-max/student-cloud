CREATE TABLE sc_assistant_owner_guard (
  account_id BIGINT NOT NULL PRIMARY KEY
);

CREATE TABLE sc_assistant_memory_settings (
  account_id BIGINT NOT NULL PRIMARY KEY,
  enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE sc_assistant_memory (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  content VARCHAR(500) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX sc_assistant_memory_owner_idx ON sc_assistant_memory(account_id,id);

CREATE TABLE sc_answer_feedback (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  account_id BIGINT NOT NULL,
  workspace_id BIGINT,
  course_id BIGINT,
  message_id VARCHAR(100) NOT NULL,
  question VARCHAR(2000) NOT NULL,
  answer TEXT NOT NULL,
  corrected_answer TEXT NOT NULL,
  note VARCHAR(2000) NOT NULL DEFAULT '',
  status VARCHAR(20) NOT NULL,
  version BIGINT NOT NULL DEFAULT 1,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT sc_answer_feedback_message_unique UNIQUE(account_id,message_id)
);
CREATE INDEX sc_answer_feedback_state_idx ON sc_answer_feedback(status,id);
CREATE INDEX sc_answer_feedback_owner_idx ON sc_answer_feedback(account_id,id);
CREATE INDEX sc_answer_feedback_course_idx ON sc_answer_feedback(course_id,id);

CREATE TABLE sc_reviewed_knowledge (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  feedback_id BIGINT NOT NULL,
  title VARCHAR(160) NOT NULL,
  answer TEXT NOT NULL,
  scope VARCHAR(16) NOT NULL,
  workspace_id BIGINT,
  course_id BIGINT,
  version BIGINT NOT NULL,
  source_feedback_version BIGINT NOT NULL,
  revoked_at BIGINT,
  superseded_at BIGINT,
  created_at BIGINT NOT NULL,
  CONSTRAINT sc_reviewed_knowledge_version_unique UNIQUE(feedback_id,version),
  CONSTRAINT sc_reviewed_knowledge_scope_check CHECK (
    (scope='GLOBAL' AND workspace_id IS NULL AND course_id IS NULL)
    OR (scope='TENANT' AND workspace_id IS NOT NULL AND course_id IS NULL)
    OR (scope='COURSE' AND workspace_id IS NULL AND course_id IS NOT NULL AND course_id>0)
  ),
  CONSTRAINT sc_reviewed_knowledge_feedback_fk FOREIGN KEY(feedback_id) REFERENCES sc_answer_feedback(id)
);
CREATE INDEX sc_reviewed_knowledge_scope_idx ON sc_reviewed_knowledge(scope,workspace_id,revoked_at,superseded_at,id);
CREATE INDEX sc_reviewed_knowledge_course_idx ON sc_reviewed_knowledge(scope,course_id,revoked_at,superseded_at,id);
