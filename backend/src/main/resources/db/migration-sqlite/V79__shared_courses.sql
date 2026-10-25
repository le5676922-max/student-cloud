CREATE TABLE shared_course (
 id TEXT PRIMARY KEY,owner_user_id INTEGER NOT NULL,tenant_id INTEGER NOT NULL,name TEXT NOT NULL,
 description TEXT NOT NULL DEFAULT '',code_hash TEXT NOT NULL UNIQUE,enabled INTEGER NOT NULL DEFAULT 1,
 quota_bytes INTEGER NOT NULL,used_bytes INTEGER NOT NULL DEFAULT 0,version INTEGER NOT NULL DEFAULT 0,
 created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL
);
CREATE INDEX idx_shared_course_owner ON shared_course(owner_user_id,created_at);
CREATE TABLE shared_course_member (
 course_id TEXT NOT NULL,user_id INTEGER NOT NULL,status TEXT NOT NULL DEFAULT 'JOINED',
 upload_status TEXT NOT NULL DEFAULT 'NONE',joined_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,
 PRIMARY KEY(course_id,user_id),FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
);
CREATE INDEX idx_shared_course_member_user ON shared_course_member(user_id,status);
CREATE TABLE shared_course_material (
 id TEXT PRIMARY KEY,course_id TEXT NOT NULL,uploader_user_id INTEGER NOT NULL,filename TEXT NOT NULL,
 size_bytes INTEGER NOT NULL,state TEXT NOT NULL,created_at INTEGER NOT NULL,
 FOREIGN KEY(course_id) REFERENCES shared_course(id) ON DELETE CASCADE
);
CREATE INDEX idx_shared_course_material_course ON shared_course_material(course_id,state,created_at);
CREATE TABLE shared_course_storage_usage(id INTEGER PRIMARY KEY,used_bytes INTEGER NOT NULL DEFAULT 0);
INSERT INTO shared_course_storage_usage(id,used_bytes) VALUES(1,0);
