CREATE TABLE sc_course_storage_budget (
  id INTEGER NOT NULL PRIMARY KEY
);
INSERT INTO sc_course_storage_budget(id) VALUES(1);

CREATE TABLE sc_course_material (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  course_id BIGINT NOT NULL,
  filename VARCHAR(255) NOT NULL,
  object_key VARCHAR(64) NOT NULL UNIQUE,
  size_bytes BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  CONSTRAINT sc_course_material_course_fk FOREIGN KEY(course_id) REFERENCES sc_shared_course(id)
);
CREATE INDEX sc_course_material_course_idx ON sc_course_material(course_id,state,id);
CREATE INDEX sc_course_material_state_idx ON sc_course_material(state,course_id);
