-- V116: Dataset physical-design fields for ARCH-005/015
-- Adds slug, partitioning, clustering, write-mode, and format hint columns.
-- Uses TEXT instead of JSONB for H2 test compatibility.

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS dataset_slug VARCHAR(200);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS domain_slug VARCHAR(200);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS partition_strategy TEXT;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS cluster_strategy TEXT;
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS write_mode VARCHAR(40) DEFAULT 'append';
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS table_format_hint VARCHAR(40);
ALTER TABLE datasets ADD COLUMN IF NOT EXISTS physical_design_version INT DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_datasets_slug ON datasets (tenant_id, dataset_slug);
