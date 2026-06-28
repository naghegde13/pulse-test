# Schema inference is deterministic (zero LLM) and is the Builder's enforced contract

Status: accepted — refines/extends ADR 0002 (LLM-grounded Builder). Settled in the
2026-06-14 Builder-architecture grill (operator + orchestrator).

## Context

ADR 0002 said "deterministic skeleton + LLM body; DDL derives from the inferred
schema," but left the schema-inference boundary fuzzy. This session verified the
ground truth at file:line:

- Schema inference **largely already exists deterministically**:
  `SchemaPropagationService.deriveBaseOutputSchema` (`:810-854`) is a `switch` over
  blueprint key (passThrough / mergeJoin / aggregateSchema / maskSchema / SCD2
  appendColumns / ingestionSchema / …). It runs on every composition mutation and
  persists per-port columns to `instance_port_schemas` (mirrored to
  `sub_pipeline_instances.output_schema`).
- But it has three problems: **(a)** an **LLM fallback** for unknown blueprints
  (`default → schemaInferenceService.inferOutputSchema`, `:843-851`), with a test
  that actively enforces it (`SchemaPropagationServiceTest.propagate_inferenceFallbackUsedWhenNoRule:578`);
  **(b)** **correctness gaps** — `BronzeToSilverCleaning` is `passThrough` (`:816`),
  ignoring its own `rename_map`/`drop_columns`/`type_coercions`; aggregate output
  *types* and same-type join name-collisions are also suspect — and the existing
  (genuinely behavioral) test suite **does not cover** these cases, so green ≠
  correct (ADR 0005); **(c)** the schema is **not consumed by codegen** — generated
  bodies `SELECT *`/project from params and are never checked against the inferred
  columns; no DDL is derived from it (dbt `materialized='table'` infers the physical
  schema from the SELECT at run time).

## Decision

1. **Schema inference is 100% deterministic — zero LLM.** A step's output columns
   come from a deterministic rule, the blueprint's **declared schema behavior**, or
   **schema discovery** (sampling the source). The LLM has **no role** in computing a
   schema. An LLM *predicting* a schema is strictly worse than a rule/declaration and
   makes the user experience unpredictable. Every apparent "needs-LLM" case resolves
   to a *missing* rule or declaration, not a real need for the model.
2. **The deterministic schema (per output port) is the ENFORCED contract.** The
   generated body must produce **exactly** those columns; a mismatch **fails loudly** —
   never ship a wrong-shaped table.
   > **SUPERSEDED (2026-06-15, ADR 0013):** the original wording here ("the **LLM-written**
   > body must produce exactly those columns; a mismatch triggers a **bounded repair
   > regeneration**; if it still mismatches, the run fails loudly") assumed an LLM-written
   > body. ADR 0013 removed the LLM from codegen entirely (the Builder is a deterministic
   > op-composition compiler), so there is **no LLM body to repair and no bounded repair
   > regeneration loop**. A mismatch fails loudly with no repair step. The "enforced
   > contract → loud-fail on mismatch" intent is unchanged; only the repair-loop mechanism
   > is gone.
3. **Validate everywhere; generate explicit DDL only where the engine doesn't make
   the table.** Every step's produced columns are verified against the contract. We
   hand-generate explicit DDL **only** where the engine doesn't create the table
   itself (PySpark bronze, external tables); we do **not** override dbt's own table
   creation — for dbt models, verify the produced table matches the contract rather
   than duplicating dbt's job.
4. **Blueprints declare their schema behavior as metadata.** To make zero-LLM real
   *and* keep "adding a blueprint easy," a blueprint declares its schema transform
   (passthrough / add-columns / aggregate-shape / mask / rename-drop-retype / …) as
   metadata the engine reads — replacing today's hardcoded Java `switch`. A brand-new
   blueprint declares its output shape; it does **not** fall back to an LLM.

The LLM's only home in the Builder remains the transform **body** (logic), per
ADR 0002 — now strictly constrained to satisfy the deterministic schema.

## Consequences

- The LLM fallback in `deriveBaseOutputSchema` is **removed**; an unknown blueprint
  with no declared schema rule is a **conflict/error**, not an LLM call
  (`propagate_inferenceFallbackUsedWhenNoRule` is rewritten to assert this).
- S1's correctness gaps are fixed **and** covered by behavioral tests authored
  independently of the implementation (the current suite is real but doesn't cover
  them): `BronzeToSilverCleaning` applies `rename_map`/`drop_columns`/`type_coercions`;
  `GenericAggregate` output types (COUNT→long; SUM int→long / decimal→double;
  AVG→double; MIN/MAX→source type); `GenericJoin` same-type name-collision →
  `right_`-prefix (include both sides).
- The schema model gains **nested struct field types** (for JsonFlatten/JsonStruct),
  sourced from schema discovery — not an LLM.
- Custom expressions (`derived_columns`) **require a declared output type** (enforced
  by validation, already the established pattern).
- The byte-exact data oracle (ADR 0009) gets its column-shape anchor: the
  deterministic schema is the expected column set.

## Considered

- **LLM-in-the-loop / "hybrid static + AI" schema** (status quo) — rejected:
  unpredictable UX; an LLM guessing a contract undermines the contract itself; every
  "needs-LLM" case is really a missing rule/declaration.
- **Hand-generate DDL everywhere (override dbt)** — rejected: fights dbt's native
  table creation for no gain; validate-against-schema achieves correctness without
  duplicating dbt's job.

## Trade-off

Adding a blueprint now requires **declaring** its schema behavior (slightly more
than leaning on an LLM guess). Accepted: a predictable, real contract beats
frictionless-but-unpredictable for a data platform, and declaration is made easy via
metadata.
