-- WS-4: Code Generation Engine

CREATE TABLE generation_runs (
    id              VARCHAR(26) PRIMARY KEY,
    pipeline_id     VARCHAR(26) NOT NULL REFERENCES pipelines(id),
    version_id      VARCHAR(26) NOT NULL REFERENCES pipeline_versions(id),
    tenant_id       VARCHAR(26) NOT NULL,
    triggered_by    VARCHAR(26) NOT NULL,
    trigger_type    VARCHAR(30) NOT NULL DEFAULT 'MANUAL',
    template_engine VARCHAR(30) NOT NULL DEFAULT 'PULSE_V1',
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    error_message   TEXT,
    metadata        JSONB DEFAULT '{}',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE generated_artifacts (
    id                  VARCHAR(26) PRIMARY KEY,
    generation_run_id   VARCHAR(26) NOT NULL REFERENCES generation_runs(id) ON DELETE CASCADE,
    file_path           VARCHAR(500) NOT NULL,
    file_type           VARCHAR(30) NOT NULL,
    content             TEXT NOT NULL,
    content_hash        VARCHAR(64) NOT NULL,
    template_name       VARCHAR(100),
    source_blueprint_id VARCHAR(26),
    manually_modified   BOOLEAN NOT NULL DEFAULT FALSE,
    metadata            JSONB DEFAULT '{}',
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_gen_runs_pipeline ON generation_runs(pipeline_id);
CREATE INDEX idx_gen_runs_version ON generation_runs(version_id);
CREATE INDEX idx_gen_runs_tenant ON generation_runs(tenant_id);
CREATE INDEX idx_gen_artifacts_run ON generated_artifacts(generation_run_id);
CREATE INDEX idx_gen_artifacts_path ON generated_artifacts(file_path);

COMMENT ON TABLE generation_runs IS 'Tracks each code generation execution for a pipeline version';
COMMENT ON TABLE generated_artifacts IS 'Individual files produced by code generation (DAGs, models, scripts, configs)';
COMMENT ON COLUMN generated_artifacts.file_type IS 'AIRFLOW_DAG, DBT_MODEL, DBT_SOURCE, PYSPARK_JOB, CONFIG_YAML, REQUIREMENTS_TXT, DOCKERFILE, TEST_SCRIPT';
COMMENT ON COLUMN generated_artifacts.manually_modified IS 'True if user edited this file after generation; re-generation preserves edits';
COMMENT ON COLUMN generation_runs.trigger_type IS 'MANUAL, COMPOSITION_CHANGE, PARAM_CHANGE, SCHEDULED';
COMMENT ON COLUMN generation_runs.status IS 'PENDING, GENERATING, COMPLETED, FAILED';
