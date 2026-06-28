-- V68: Dataset grain — the entity-level granularity describing what one row represents.
-- Examples: "per account per customer per month", "per trade per day", "per employee"

ALTER TABLE datasets ADD COLUMN IF NOT EXISTS grain JSONB;

COMMENT ON COLUMN datasets.grain IS
  'The granularity of the dataset — what combination of entities/dimensions defines one record.
   Stored as a structured object:
   {
     "description": "One record per account per customer per month",
     "dimensions": ["account", "customer", "month"]
   }
   The dimensions array lists each entity or time component that forms the grain.';

-- Seed grain for existing datasets
UPDATE datasets SET grain = '{"description": "One record per application", "dimensions": ["application"]}'
WHERE id = '01JDS0LOS0APPLICATIONS01';

UPDATE datasets SET grain = '{"description": "One record per underwriting case", "dimensions": ["underwriting_case"]}'
WHERE id = '01JDS0LOS0UNDERWRITING01';

UPDATE datasets SET grain = '{"description": "One record per payment per day", "dimensions": ["payment", "day"]}'
WHERE id = '01JDS0PAY0DAILY0SUMMARY1';

UPDATE datasets SET grain = '{"description": "One record per credit file per borrower", "dimensions": ["credit_file", "borrower"]}'
WHERE id = '01JDS0CRD0CREDIT0FILES01';

UPDATE datasets SET grain = '{"description": "One record per loan per pool per month", "dimensions": ["loan", "pool", "month"]}'
WHERE id = '01JDS0INV0LOAN0POOLS0001';
