-- Agent C / V83: GCP Secret Manager support + domain slugs + readiness index.

-- 1. Domain slugs for deterministic secret naming
ALTER TABLE domains ADD COLUMN slug VARCHAR(100);

UPDATE domains
SET slug = LOWER(REGEXP_REPLACE(REGEXP_REPLACE(name, '[^a-zA-Z0-9]+', '-', 'g'), '^-|-$', '', 'g'));

-- Collision fix (defensive — existing unique (tenant_id, name) makes collisions rare but slug
-- normalization can still collapse e.g. "Capital Markets" and "capital-markets").
UPDATE domains d SET slug = slug || '-' || RIGHT(d.id, 4)
WHERE EXISTS (
    SELECT 1 FROM domains d2
    WHERE d2.tenant_id = d.tenant_id AND d2.slug = d.slug AND d2.id < d.id
);

ALTER TABLE domains ALTER COLUMN slug SET NOT NULL;
ALTER TABLE domains ADD CONSTRAINT uq_domain_tenant_slug UNIQUE (tenant_id, slug);

-- 2. Audit column for which GCP project held the secret at write time
ALTER TABLE credential_profiles ADD COLUMN secret_project_id VARCHAR(100);

-- 3. Readiness lookup index
CREATE INDEX idx_credprof_ci_env ON credential_profiles(connector_instance_id, environment);
