ALTER TABLE cobol_discovery_messages
    ADD COLUMN metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
