-- SQLite TEXT already supports the bounded expanded retrieval text.
ALTER TABLE learning_material ADD COLUMN index_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE learning_material ADD COLUMN covered_chars INTEGER NULL;
ALTER TABLE learning_material ADD COLUMN indexed_pages INTEGER NULL;
ALTER TABLE learning_material ADD COLUMN total_pages INTEGER NULL;
ALTER TABLE learning_material ADD COLUMN truncated BOOLEAN NULL;
ALTER TABLE learning_material ADD COLUMN index_notice VARCHAR(500) NULL;
