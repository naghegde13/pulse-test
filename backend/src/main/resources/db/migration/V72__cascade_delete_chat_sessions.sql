ALTER TABLE chat_sessions
    DROP CONSTRAINT IF EXISTS chat_sessions_pipeline_id_fkey,
    ADD CONSTRAINT chat_sessions_pipeline_id_fkey
        FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE SET NULL;
