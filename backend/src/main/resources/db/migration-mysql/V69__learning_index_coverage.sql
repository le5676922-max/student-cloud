-- Retrieval text is independent of chat attachments. 240k UTF-16 characters fit MEDIUMTEXT/utf8mb4.
ALTER TABLE learning_material MODIFY COLUMN content MEDIUMTEXT NOT NULL;
ALTER TABLE learning_material ADD COLUMN index_version INT NOT NULL DEFAULT 1;
ALTER TABLE learning_material ADD COLUMN covered_chars INT NULL;
ALTER TABLE learning_material ADD COLUMN indexed_pages INT NULL;
ALTER TABLE learning_material ADD COLUMN total_pages INT NULL;
ALTER TABLE learning_material ADD COLUMN truncated BOOLEAN NULL;
ALTER TABLE learning_material ADD COLUMN index_notice VARCHAR(500) NULL;
-- NULL coverage on old indexes is deliberate: their original extraction scope is not provable.
