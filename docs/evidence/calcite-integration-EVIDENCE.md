# CALCITE SERIAL-JOIN INTEGRATION — EVIDENCE

Worktree: `/Users/aameradam/projects/dev/pulse-wt/calcite-integration`
Branch: `build/calcite-integration` (base: "Merge builder lane (squash)")
Scope delivered: **parts (a) + (c)**. Part (b) **deferred** (see bottom).

Per `docs/evidence/calcite-INTEGRATION-PLAN.md`. Implements SPEC #6 / ADR 0024,
honors ADR 0011 (deterministic, zero-LLM, loud-fail) + ADR 0009 (byte-exact).

> NOTE on the validator API: the plan quotes
> `resolveSqlModelSchema(steps, inputColumns, declaredOutputSchema)`, but the
> **shipped** validator signature is
> `resolveSqlModelSchema(List<Map> inputColumns, List<SqlModelStep> steps, List<Map> declaredOutputSchema)`
> (inputColumns FIRST) and the record is `SqlModelStep(name, sql, materialize)`.
> The integration matches the real code (verified at
> `CalciteSqlModelValidator.java:236` / `:487`).

---

## (a) sql-model OUT schema — Calcite-PRIMARY, declared-schema FALLBACK, never LLM

**What changed**

- `SqlModelSchemaService` — now injects `CalciteSqlModelValidator`; the inert
  `resolveByCalcite(String,Schema)` seam is replaced by the real Calcite-primary
  chain resolver. Calcite derive (primary) → `declared_schema`/`declared_output_schema`
  §A.6 fallback → loud-fail. Never touches `schemaInferenceService` / any LLM.
  - `resolveByCalcite(List<SqlModelStep>, Schema, List<Map>)` —
    `backend/src/main/java/com/pulse/pipeline/opengine/SqlModelSchemaService.java:79`
  - `resolve(Schema, ResolvedConfig)` op-engine seam (steps→Calcite; else declared) — `:96`
  - `readSteps` / `readDeclared` config readers + Schema⇄column-list round-trip — `:110`+
- `SqlModelOp` — Calcite-primary when the seam is wired; legacy declared-only as a
  dependency-free fallback (no-arg ctor kept).
  - `apply(...)` delegates to `sqlModelSchemaService.resolve(in, cfg)` —
    `backend/src/main/java/com/pulse/pipeline/opengine/ops/SqlModelOp.java:56`
- Op-engine call site uses the Calcite-primary path:
  - `SchemaOpEngine(SqlModelSchemaService)` ctor builds the registry with the seam —
    `backend/src/main/java/com/pulse/pipeline/opengine/SchemaOpEngine.java:25`
  - `OpRegistry(SqlModelSchemaService)` passes the seam to `new SqlModelOp(...)` —
    `backend/src/main/java/com/pulse/pipeline/opengine/OpRegistry.java:53,89`

**ADR-0011 never-LLM**: the resolution path is Calcite → declared → loud-fail only.
`SchemaOpEngine`/`OpRegistry`/`SqlModelOp` hold **no** `schemaInferenceService`
reference at all; `CalciteSqlModelValidator` is documented & built to never call it.

**Tests**
- `backend/src/test/java/com/pulse/pipeline/opengine/SqlModelSchemaServiceTest.java`
  - `calcitePrimaryDerivesOutputFromSteps` — (input + steps) → derived OUT via Calcite
  - `calciteErrorFallsBackToDeclaredSchema` — Calcite error + declared → declared (§A.6)
  - `calciteErrorWithNoDeclaredSchemaLoudFails` — `BuildFailureException` loud-fail
  - `noStepsAndNoDeclaredLoudFails` — `OpEngineException` loud-fail
- `backend/src/test/java/com/pulse/pipeline/opengine/SchemaOpEngineTest.java`
  - `sqlModelOpResolvesCalcitePrimaryThroughWalker` — Calcite-primary end-to-end via `applyOpList`

Command + result:
`cd backend && ./gradlew fastPrTest --tests "*SqlModel*" --tests "*CalciteSqlModel*"`
→ **BUILD SUCCESSFUL** (all green).

---

## (c) `[[ ]]` mnemonic lowering + dbt `--vars` in CodeGenerationService

All helpers in
`backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`:

- `MNEMONIC_TOKEN = Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]")` — `:4982`
- `validateMnemonics(String)` → `DateMnemonic.validateOrThrow` (§c.1 loud-fail typos) — `:4990`
- `mnemonicSlug(String)` (6-G07: lowercase + non-alnum→`_`, `pulse_` prefix) — `:5003`
- `collectMnemonicSlugs(inst)` (recursive param scan, dedup by slug, §c.1 validate) — `:5016`
- `lowerDbtMnemonics(sql)` → `[[m]]` → `{{ var('pulse_<slug>') }}`; byte-identical when
  no mnemonics (ADR 0009) — `:5048`
- `substituteMnemonicsForRuntime(sql)` (§c.4 PySpark/SourceSQL runtime substitution;
  used by part-(b) once it lands) — `:5066`
- `buildDbtVarsJson(slugMap)` → `{"pulse_<slug>":"{{ pulse_resolve_mnemonic('<m>', ds) }}"}`;
  null when no mnemonics → invocation unchanged (ADR 0009) — `:5082`
- `assertNoMnemonicResidue(artifacts)` (§c.6 load-bearing: no `[[ ]]` survives) — `:5111`

**Wiring**
- dbt model SQL rewrite at the artifact boundary (covers all dbt model/snapshot/router/
  reuse-wrapper paths): `lowerDbtMnemonics(...)` at
  `generateDbtModels` createArtifact sites — `:1664,1681,1690,1696,1820`
- dbt `--vars` appended at the BashOperator anchor `dbt build --select tag:` (only when
  the task has mnemonics; else byte-identical) — `:625`
- runtime macro `pulse_resolve_mnemonic` (def + `user_defined_macros=` on the DAG) and
  `import pulse_dates`, emitted only when `hasDateMnemonics` — `:443,461,498`
- `pulse_dates/__init__.py` RUNTIME_SUPPORT shipped when mnemonics present (emit-if-absent
  dedups vs the advance-time path) — `:220`
- §c.6 sweep runs before persistence — `:227`

**Scanner**: `ForbiddenTokenScanner` has no `[[ ]]` rule; after lowering, dbt →
`{{ var(...) }}`, so it stays green (asserted in test).

**Tests**
- `backend/src/test/java/com/pulse/codegen/service/MnemonicLoweringTest.java`
  - `slugRuleLowercasesAndUnderscoresNonAlnumWithPulsePrefix` (PBD-1, RUN_DATE, NBDOM(2), …)
  - `dbtRewriteLowersMnemonicToVarAndLeavesNoBrackets` (`[[m]]`→`{{ var }}`, no `[[`/`]]`)
  - `dbtRewriteIsByteIdenticalWhenNoMnemonics` (ADR 0009)
  - `typoMnemonicLoudFails` / `dbtRewriteAlsoLoudFailsOnTypo` (§c.1)
  - `loweredDbtModelHasNoBracketsAndPassesForbiddenScanner` (§c.6 + scanner green)

Command + result:
`cd backend && ./gradlew fastPrTest --tests "*Mnemonic*" --tests "*ForbiddenTokenScanner*" --tests "*DateMnemonic*"`
→ **BUILD SUCCESSFUL** (all green).

---

## fastPrTest (full)

`cd backend && ./gradlew fastPrTest`
→ **3044 tests completed, 3 failed** — exactly the 3 KNOWN pre-existing failures:
`CodegenExampleSharingRegressionTest`, `EndpointReferenceContractTest`,
`AdapterConfigVsFormFieldContractTest`. **No new failures.**
(H2 fast lane: Flyway OFF, so V153 migration SQL is irrelevant here.)

The five `SchemaPropagationE2EIntegrationIT` failures seen in a `./gradlew test` run
are `*IT.java` integration tests excluded from `fastPrTest`; they fail only because
no local Postgres is up (connection refused), unrelated to these changes.

---

## (b) DEFERRED — SourceSQL source-prepare

Part (b) (the `source_query` "source-prepare" branch in `ingestionSchema()` that opens
a live JDBC connection and reads `ResultSetMetaData`) is **NOT** implemented. Per the
plan it needs a brand-new `connectorJdbcResolver` collaborator (connector→`DataSource`)
injected into `SchemaPropagationService` — which has no connector repos today — plus a
live source. No such collaborator exists. The `[[ ]]` substitution helper that part (b)
will use is already in place (`substituteMnemonicsForRuntime`), and `SourceSQL`'s
`source_query` is not emitted anywhere in codegen yet, so nothing in part (c) depends on
part (b). When part (b) lands it wires the new resolver + `jdbcTypeToPulse` map per plan
§(b)/§C.2 and the symmetric ADR-0011 never-LLM assertion.
