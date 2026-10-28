CREATE TABLE sc_chat_answer (
  message_id VARCHAR(100) NOT NULL PRIMARY KEY,
  account_id BIGINT NOT NULL,
  workspace_id BIGINT,
  course_id BIGINT,
  question VARCHAR(2000) NOT NULL,
  answer TEXT NOT NULL,
  created_at BIGINT NOT NULL
);
CREATE INDEX sc_chat_answer_owner_idx ON sc_chat_answer(account_id,created_at);
