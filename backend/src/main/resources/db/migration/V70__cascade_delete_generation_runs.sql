-- V70: Add ON DELETE CASCADE to generation_runs FKs so pipeline/version deletes succeed.

ALTER TABLE generation_runs
    DROP CONSTRAINT IF EXISTS generation_runs_version_id_fkey,
    ADD CONSTRAINT generation_runs_version_id_fkey
        FOREIGN KEY (version_id) REFERENCES pipeline_versions(id) ON DELETE CASCADE;

ALTER TABLE generation_runs
    DROP CONSTRAINT IF EXISTS generation_runs_pipeline_id_fkey,
    ADD CONSTRAINT generation_runs_pipeline_id_fkey
        FOREIGN KEY (pipeline_id) REFERENCES pipelines(id) ON DELETE CASCADE;
