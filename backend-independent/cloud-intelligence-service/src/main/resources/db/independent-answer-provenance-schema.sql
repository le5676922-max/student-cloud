-- Unknown historical context is private by default. New answers set the flag explicitly.
ALTER TABLE sc_chat_answer ADD COLUMN private_context BOOLEAN NOT NULL DEFAULT TRUE;
