-- V88: Upgrade usage_guidance on dbt-emitting transformation blueprints to explicitly
-- disclaim data quality. The architectural decision is "all DQ is GX, no dbt tests"
-- (locked decision #6 in the redesign plan), but the blueprint names "Cleaning",
-- "Masking", and "SCD2Dimension" are ambiguous to architecturally-conscious users
-- who hear "cleaning" and assume DQ filtering. The disclaimer makes the metadata
-- self-explanatory for any future consumer (agent, UI, REST client).
--
-- Closes finding #27 in docs/RUNTIME_E2E_FINDINGS.md.

UPDATE blueprints
SET usage_guidance = jsonb_set(
        COALESCE(usage_guidance, '{}'::jsonb),
        '{when_to_use}',
        to_jsonb('Transform raw data: type casts, trims, null coercion, dedup. NO data quality rules — for DQ use DQValidator (GX).'::text)
    )
WHERE blueprint_key = 'BronzeToSilverCleaning';

UPDATE blueprints
SET usage_guidance = jsonb_set(
        COALESCE(usage_guidance, '{}'::jsonb),
        '{when_to_use}',
        to_jsonb('Mask PII columns via hash, redact, tokenize, or encrypt. NO data quality rules — for DQ use DQValidator (GX).'::text)
    )
WHERE blueprint_key = 'PIIMasking';

UPDATE blueprints
SET usage_guidance = jsonb_set(
        COALESCE(usage_guidance, '{}'::jsonb),
        '{when_to_use}',
        to_jsonb('Track full history for mutable master data (employees, customers) via SCD2 snapshot. NO data quality rules — for DQ use DQValidator (GX).'::text)
    )
WHERE blueprint_key = 'SCD2Dimension';
