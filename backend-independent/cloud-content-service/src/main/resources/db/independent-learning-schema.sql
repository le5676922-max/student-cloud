CREATE TABLE sc_learning_owner_guard (account_id BIGINT PRIMARY KEY);
CREATE TABLE sc_learning_course (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_id BIGINT NOT NULL,
  name VARCHAR(120) NOT NULL,
  semester VARCHAR(80) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL
);
CREATE INDEX ix_learning_course_owner ON sc_learning_course(owner_id);
CREATE TABLE sc_learning_material (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  course_id BIGINT NOT NULL,
  storage_key VARCHAR(100) NOT NULL,
  path VARCHAR(500) NOT NULL,
  name VARCHAR(255) NOT NULL,
  tags VARCHAR(500) NOT NULL,
  content_hash VARCHAR(64) NOT NULL,
  indexed_text MEDIUMTEXT NOT NULL,
  covered_chars INT NOT NULL,
  truncated BOOLEAN NOT NULL,
  indexed_pages INT,
  total_pages INT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT fk_learning_course FOREIGN KEY(course_id) REFERENCES sc_learning_course(id) ON DELETE CASCADE,
  CONSTRAINT uq_learning_reference UNIQUE(course_id,storage_key,path)
);
