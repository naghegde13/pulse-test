-- Canonical domain identity for systems of record.

ALTER TABLE systems_of_record
    ADD COLUMN IF NOT EXISTS domain_id VARCHAR(26);

UPDATE systems_of_record sor
SET domain_id = d.id
FROM domains d
WHERE sor.domain_id IS NULL
  AND d.tenant_id = sor.tenant_id
  AND d.name = sor.domain_name;

ALTER TABLE systems_of_record
    ADD CONSTRAINT fk_sors_domain
        FOREIGN KEY (domain_id) REFERENCES domains(id) ON DELETE SET NULL;

CREATE INDEX IF NOT EXISTS idx_sors_domain_id ON systems_of_record(domain_id);
