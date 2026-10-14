-- Vectors remain private and bounded in the existing per-owner material index.
-- Old rows are intentionally NULL: opening/searching a library never sends old files to a provider.
ALTER TABLE learning_material ADD COLUMN embedding_json MEDIUMTEXT NULL;
ALTER TABLE learning_material ADD COLUMN embedding_signature VARCHAR(100) NULL;
ALTER TABLE learning_material ADD COLUMN semantic_segments INT NULL;
ALTER TABLE learning_material ADD COLUMN semantic_notice VARCHAR(500) NULL;
