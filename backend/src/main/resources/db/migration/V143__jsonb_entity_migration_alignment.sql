-- =============================================================================
-- V143 -- Align jsonb-declared JPA entity columns with Postgres column types.
-- =============================================================================
-- Background: 9 entity columns declare columnDefinition = "jsonb" but were
-- created as TEXT by earlier migrations (V116 for the contract tables, V117
-- for runtime_projection_ddl_statements, V127 for the datasets columns).
-- With ddl-auto=validate the backend cannot boot against Postgres until
-- these align -- Hibernate reads `TEXT` as `Types#VARCHAR` and reports
-- `Schema-validation: wrong column type encountered`, refusing to start
-- the SessionFactory.
--
-- Existing H2-backed unit tests do not exercise this drift (H2 treats TEXT
-- and JSONB equivalently), so the rehearsal session on 2026-05-25 was the
-- first time the mismatch reached real Postgres. See:
--   docs/verification/artifacts/acme-lending-guided-human-rehearsal-2026-05-25.md
--   BUG-2026-05-25-01 / PKT-FINAL-1.
--
-- Affected columns (9 total):
--   table_contracts.partition_spec, .layout_spec, .primary_key_columns,
--                   .business_date_columns, .provenance      (V116, line 31-40)
--   dataset_landing_contracts.provenance                     (V116, line 71)
--   datasets.cluster_strategy, .partition_strategy           (V127, line 7-8)
--   runtime_projection_ddl_statements.dependency_statement_ids
--                                                            (V117, line 42)
--
-- The conversion uses `NULLIF(<col>::text,'')::jsonb` so any pre-existing
-- rows with empty strings become NULL rather than failing the cast. Any rows
-- that hold non-empty, non-JSON strings will fail the cast -- that is a
-- real data-shape bug surfaced by this migration (intentional), not a
-- migration regression.
-- =============================================================================

ALTER TABLE table_contracts            ALTER COLUMN partition_spec        TYPE jsonb USING NULLIF(partition_spec::text,'')::jsonb;
ALTER TABLE table_contracts            ALTER COLUMN layout_spec           TYPE jsonb USING NULLIF(layout_spec::text,'')::jsonb;
ALTER TABLE table_contracts            ALTER COLUMN primary_key_columns   TYPE jsonb USING NULLIF(primary_key_columns::text,'')::jsonb;
ALTER TABLE table_contracts            ALTER COLUMN business_date_columns TYPE jsonb USING NULLIF(business_date_columns::text,'')::jsonb;
ALTER TABLE table_contracts            ALTER COLUMN provenance            TYPE jsonb USING NULLIF(provenance::text,'')::jsonb;
ALTER TABLE dataset_landing_contracts  ALTER COLUMN provenance            TYPE jsonb USING NULLIF(provenance::text,'')::jsonb;
ALTER TABLE datasets                   ALTER COLUMN cluster_strategy      TYPE jsonb USING NULLIF(cluster_strategy::text,'')::jsonb;
ALTER TABLE datasets                   ALTER COLUMN partition_strategy    TYPE jsonb USING NULLIF(partition_strategy::text,'')::jsonb;
ALTER TABLE runtime_projection_ddl_statements ALTER COLUMN dependency_statement_ids TYPE jsonb USING NULLIF(dependency_statement_ids::text,'')::jsonb;
