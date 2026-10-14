ALTER TABLE learning_material ADD COLUMN embedding_json TEXT NULL;
ALTER TABLE learning_material ADD COLUMN embedding_signature VARCHAR(100) NULL;
ALTER TABLE learning_material ADD COLUMN semantic_segments INTEGER NULL;
ALTER TABLE learning_material ADD COLUMN semantic_notice VARCHAR(500) NULL;
