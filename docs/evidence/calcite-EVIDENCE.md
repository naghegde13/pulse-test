# CALCITE-PHASE-2 — Build Evidence

> Lane: **Calcite** (`build/calcite`). Implements SPEC #6 `SPEC-calcite-sql-model.md` §A, §B (chain), §A.6
> (fallback), §D.3 (`[[ … ]]` typing) — the **schema-deriving `sql-model` validator** + its tests.
> Scaffolding (committed at `c96c58c`) was reused, not rebuilt. Integration into `SchemaPropagationService` /
> `CodeGenerationService` and the §C SourceSQL/JDBC path, §D.4 codegen lowering, and the V153 seed are
> **deferred to post-merge** — see `calcite-INTEGRATION-PLAN.md` (this lane does not touch those files).

## Files created (this lane)

| File | Lines | Role |
|---|---|---|
| `backend/src/main/java/com/pulse/expression/service/CalciteSqlModelValidator.java` | 515 | The schema-deriving validator + chain walker + §A.6 fallback (`@Service`). |
| `backend/src/main/java/com/pulse/expression/service/SparkSqlFunctionRegistry.java` | 129 | Extensible Spark-SQL function set composed with Calcite `SqlStdOperatorTable` (§A.2). |
| `backend/src/test/java/com/pulse/expression/service/CalciteSqlModelValidatorTest.java` | 405 | 26-test unit suite (§F 1–3 + never-LLM + determinism). |

## Files reused (scaffolding, `c96c58c` — NOT recreated)

`PulseCalciteTypeMap.java` (the bidirectional PULSE↔Calcite type map, nested), `BuildFailure.java` /
`BuildFailureException.java` (failure types + the 9 `Cause`s), `SqlParserConfigFactory.java` (shared Babel
config), `DateMnemonicTokenScanner.java` (the `[[ … ]]` scanner).

### Scaffolding change (1 file, behavior-bug fix, intent-preserving)

`DateMnemonicTokenScanner.DATE_PLACEHOLDER` was `DATE '1970-01-01'`. The Babel/Spark-conformance parser
rejects a bare `DATE '…'` typed literal in operand positions (e.g. function-call args), which breaks the
scanner's own documented goal ("type as DATE in ANY position"). Changed to `CAST('1970-01-01' AS DATE)`,
which parses + types as DATE uniformly. Javadoc updated to record this and the separate **Babel `DATEDIFF`
limitation** below.

## Validator capabilities (file:line)

- **Single-statement validation + schema derivation** — `CalciteSqlModelValidator.java:94`
  `validateStatement(sql, catalog)` → `SqlModelResult` (PULSE column list **or** `BuildFailure`). Builds a
  Calcite root schema with one virtual table per catalog relation (`:262` `buildRootSchema`), a `SqlValidator`
  over the Babel conformance + Spark function table (`:277` `newValidator`), validates, and reverse-maps the
  terminal `SELECT`'s validated row type to PULSE columns. **Pure** — no LLM, no network, no clock (§A.7).
- **`sql-model` chain walker** — `:171` `validateChain(inputSchema, steps)`: walks `steps[{name,sql,materialize}]`
  in order against an accumulating catalog (`input` + every prior step's derived schema), registers each step
  under its `name`, returns the **last** step's schema. Unique names + reserved-`input` collision enforced.
  `materialize` is schema-irrelevant (ignored here, §B.3).
- **`[[ … ]]` → DATE typing (§D.3)** — `validateStatement` calls `DateMnemonicTokenScanner.substituteDatePlaceholders`
  before parse; the token types as DATE in predicate operands and (non-`DATEDIFF`) function args. The date value
  is **never resolved** (no clock). `com.pulse.common.text.DateMnemonic` is reused unchanged.
- **Declare-schema EITHER/OR fallback (§A.6)** — `:236` `resolveSqlModelSchema(input, steps, declared)`:
  derivation primary (wins on success); on Calcite **error** → declared schema if present (warning logged),
  else `throw BuildFailureException` (loud fail). No cross-check, no fail-on-mismatch. **Never** calls
  `schemaInferenceService` / any LLM (ADR 0011).
- **Author-error checks (§A.4)** — `:334` `checkUnaliasedExpression` (expression output column with no explicit
  alias → loud fail, no `EXPR$n`); `:404` `checkDuplicateOutputNames` (duplicate output name → loud fail).
- **Failure classification (§A.5)** — `:436` `contextFailure` maps Calcite's unified "not found" exception
  family to `UNKNOWN_COLUMN` / `UNKNOWN_RELATION` / `UNREGISTERED_FUNCTION` / `UNRESOLVED_TYPE`. Column-shaped
  messages are checked **before** the relation check (Calcite phrases an unknown column as "Column 'x' not
  found in **any table**", which also contains "table"); ambiguous misses default to `UNKNOWN_COLUMN` — the
  honest limitation already documented in `BuildFailure.java`.
- **Spark function registry (§A.2)** — `SparkSqlFunctionRegistry.operatorTable()` chains
  `SqlStdOperatorTable.instance()` (ANSI/std built-ins, aggregates, window machinery — free) with a
  `ListSqlOperatorTable` of the Spark spellings PULSE codegen emits (`SHA2`, `MD5`, `CONCAT_WS`, `CONCAT`,
  `REGEXP_REPLACE`, `LPAD`/`RPAD`, `LENGTH`, `REPEAT`, `SPLIT`, `NVL`, `TRY_CAST`, `DATEDIFF`,
  `DATE_ADD`/`DATE_SUB`, `DATE_FORMAT`, `TO_DATE`/`TO_TIMESTAMP`). Extensible; an unregistered function is a
  loud `UNREGISTERED_FUNCTION` build-fail.

## Known parser limitation (honest, verified)

The Babel grammar defines a **special `DATEDIFF`** form that accepts only a **simple identifier** as its
first operand — it rejects any non-identifier expression there, including both the `CAST(…)` DATE placeholder
and a `DATE '…'` literal. Verified by probe: `DATEDIFF(d1, d2)` parses; `DATEDIFF(CAST('1970-01-01' AS DATE), d)`
does not (`Encountered "(" …`). This is a **parser-grammar** limitation, not a typing one — the placeholder
types as DATE correctly everywhere the parser admits it (`DATE_SUB`/`DATE_ADD` args, predicate operands).
Recorded in `DateMnemonicTokenScanner.java` javadoc. The §D.3 test (`dateMnemonic_typesAsDateInPredicateAndFunctionArg`)
therefore exercises the function-arg case via `DATE_SUB` and the predicate case via `>= [[ BOM-1 ]]`.

## Test command + ACTUAL results

```
cd /Users/aameradam/projects/dev/pulse-wt/calcite/backend
./gradlew test --tests "com.pulse.expression.service.CalciteSqlModelValidator*"
```

**Result: BUILD SUCCESSFUL — `CalciteSqlModelValidatorTest`: 26 tests, 0 failures, 0 errors, 0 skipped.**
(Source: `build/test-results/test/TEST-com.pulse.expression.service.CalciteSqlModelValidatorTest.xml` line 2.)

Regression check — existing parse-only suite still green:
```
./gradlew test --tests "com.pulse.expression.service.*"
```
**`ExpressionValidationServiceTest`: 9 tests, 0 failures, 0 errors** — no regression.

### Coverage map (test → SPEC §F)

- **§F-1 (input+SQL → schema):** rename, bare-column, cast, join, aggregate (`SUM`/`COUNT(*)`), CTE, window
  (`ROW_NUMBER OVER`), struct round-trip, list round-trip, multi-step chain, `SHA2` (registered Spark fn),
  `[[ … ]]` DATE typing.
- **§F-2 (invalid → BuildFailure):** parse error, unknown column, unknown relation, unresolved type,
  unregistered function, unaliased expression, duplicate output name, duplicate step name.
- **§F-3 (declare-schema 3-way):** Calcite-valid → derived wins; Calcite error + declared → declared used
  (warning logged — captured in the XML `system-out`); Calcite error + no declared → `BuildFailureException`.
- **§B.3:** `materialize` true/false → identical schema.
- **§A.7 / ADR-0011:** determinism (same inputs → equal schema); `neverLlm_noSchemaInferenceDependency`
  (reflective assertion that no field type names schema-inference / LLM / model providers).

## Out of scope for this lane (deferred to `calcite-INTEGRATION-PLAN.md`, applied post-merge)

- Wiring the validator into `SchemaPropagationService` rule-27 (the op-walker, Builder plan).
- §C `SourceSQL` JDBC source-prepare branch (NOT Calcite).
- §D.4 codegen `[[ … ]]` lowering (`CodeGenerationService`, dbt `--vars` / PySpark substitution) and the
  §B.3 Mode-aware `materialize` emission.
- The V153 `SqlModel` / `SourceSQL` blueprint seed (catalog-seed plan owns the single migration).

No `build.gradle.kts` change (calcite-core/babel 1.39.0 already declared at `:99-100`). No edits to
`SchemaPropagationService.java` or `CodeGenerationService.java`.
