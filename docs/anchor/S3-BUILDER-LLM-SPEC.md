# S3 — Put the LLM into the Builder for transform bodies (ADR 0002) — blind-implementer spec

> Status: **⛔ SUPERSEDED — DO NOT dispatch or merge.** S3-as-built (branch `s3-llm-builder`) is the SQL-form special case; its premise is being REDESIGNED in the merged Builder-architecture session (see `docs/anchor/BUILDER-ARCHITECTURE-GRILL.md` + PULSE-MAP). Kept as input only.
> Implements ADR 0002 (`docs/adr/0002-llm-grounded-builder.md`) and follows the
> handoff mechanics of ADR 0008 (`docs/adr/0008-agent-handoff-protocol.md`) and
> the behavioral-test method of ADR 0004 (`docs/adr/0004-behavioral-test-method.md`).
>
> When dispatching to an external implementer (Codex / Droid) or a blind Claude
> agent, paste **only** the `## Self-contained context`, `## Task`, `## Constraints`,
> and `## Worktree & handoff` sections. **Never** paste `## Behavioral test
> (evaluator-only)` — the implementer must not see the oracle (ADR 0004 §2, ADR 0008 §2).

---

## Self-contained context (the implementer has no PULSE background)

PULSE's **Builder** = `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`.
It generates Airflow DAGs, PySpark jobs, dbt models, etc. from a saved pipeline
**version** (a composition of `SubPipelineInstance`s wired by ports). Today **every
line of generated code comes from static Java templates — there is no LLM call
anywhere in the `com.pulse.codegen` package** (verified: `grep -rln
"HttpURLConnection\|callLLM\|chat/completions" backend/src/main/java/com/pulse/codegen/`
returns nothing). ADR 0002 changes that for **transform bodies only**.

### A. The dbt-model generation path and the exact seam

`CodeGenerationService.generateDbtModels(...)` starts at
`CodeGenerationService.java:1584`. It is invoked from the main pipeline at
`CodeGenerationService.java:190` (`artifacts.addAll(generateDbtModels(...))`).

For each `SubPipelineInstance inst`, it looks up the blueprint, and only processes
`TRANSFORM` / `MODELING` categories (`:1600`):
> `if (!"TRANSFORM".equals(category) && !"MODELING".equals(category)) continue;`

After several early-`continue` special cases (reuse wrapper `:1619`, `SCD2Dimension`
`:1642`, `SnapshotModel` `:1650`, `GenericRouter` `:1657`), it reaches **the
transform-body dispatch — THIS IS THE SEAM** (`CodeGenerationService.java:1668-1704`):
> ```java
> StringBuilder body = new StringBuilder();
> if ("GenericJoin".equals(bpKey)) {
>     body.append(generateJoinSqlBody(inst, slug));
> } else if ("GenericAggregate".equals(bpKey) || ...) {
>     body.append(generateAggregateSqlBody(inst, slug));
> } else if ("BronzeToSilverCleaning".equals(bpKey)) {
>     body.append(generateBronzeToSilverCleaningSqlBody(inst, slug));
> } else if ("PIIMasking".equals(bpKey)) { ...
> ... (one branch per blueprint) ...
> } else {
>     // Default fallback body — SELECT *, current_timestamp() ... FROM {{ source(...) }}
> }
> ```

The per-blueprint `generate*SqlBody(...)` methods return **only the SELECT body**
(no `{{ config(...) }}` header). Example — the current B2S body is a pure
passthrough (`CodeGenerationService.java:2345-2348`):
> ```java
> private String generateBronzeToSilverCleaningSqlBody(SubPipelineInstance inst, String slug) {
>     String upstreamRef = resolveUpstreamFromExpr(inst, "raw_input", slug);
>     return "SELECT *\nFROM " + upstreamRef + "\n";
> }
> ```

**Everything AFTER the seam is the deterministic skeleton and MUST stay
template/code-driven** (`CodeGenerationService.java:1706-1791`):
- materialization decision (`materialized` = `table` / `incremental` /
  `pulse_delta_table` / `pulse_delta_incremental_merge`) at `:1706-1719`;
- `file_format`, `location_root`, `partition_by`, `unique_key`, pre-hooks,
  tags, alias — all computed and emitted into the `{{ config(...) }}` block at
  `:1744-1782`;
- `addProcessedAtColumn(body.toString())` at `:1783` and the incremental guard
  wrap at `:1784-1788`;
- the final `sql.append(bodyWithProcessedAt)` at `:1789` and `createArtifact(...)`
  at `:1790`.

So the body string produced at the seam (`:1668-1704`) is the **only** thing the
LLM should replace. The config header, materialization, refs/sources resolution,
partitioning, and audit-column injection all run unchanged on whatever body string
is present.

**Skeleton context available at the seam** (already in scope at `:1668`):
- `inst` (`SubPipelineInstance`) — `inst.getName()`, `inst.getId()`,
  `inst.getParams()` (a `Map<String,Object>`), `inst.getVersionId()`.
- `bp` (`Blueprint`) — `bp.getName()`, `bp.getBlueprintKey()`, `bp.getDescription()`,
  `bp.getCategory()`, `bp.getCodegenHints()`.
- `bpKey`, `entitySlug` (`slugify(inst.getName())`), `slug` (pipeline slug),
  `resolvedLayer` (`silver`/`gold`, `:1608`), `fileFormat` (`:1611`),
  `compileNode` (the compile-plan node map), `domainSlug`.
- **Upstream refs/sources** are resolved by
  `resolveUpstreamFromExpr(inst, portName, slug)` (`CodeGenerationService.java:3069-3095`).
  It walks `PortWiring`s to the upstream instance and returns a dbt expression:
  for an INGESTION upstream it returns `{{ source('bronze_<srcSlug>', '<table>') }}`
  (`:3084`); otherwise `{{ ref('<model>') }}` (`:3086`); if nothing is wired it
  returns `{{ source('bronze_<slug>', '<slug>_input') }}` (`:3094`). **The LLM body
  MUST read from the upstream ref string this method returns** — do not let the LLM
  invent table names.

### B. Blueprint grounding inputs (description + example code)

`Blueprint` (`backend/src/main/java/com/pulse/blueprint/model/Blueprint.java`):
- `getDescription()` (`:122-123`) — the plain-English description
  (e.g. B2S: *"Takes raw, messy data and cleans it up: fixes data types, removes
  junk rows, standardizes formats..."*, `V7__blueprints_catalog.sql:113`).
- `getParamsSchema()` (`:128`) — typed param definitions.
- `getCodegenHints()` (`:160`) — JSON map; the example file names live under
  `codegen_hints.example_keys`.

`CodegenExampleService`
(`backend/src/main/java/com/pulse/codegen/service/CodegenExampleService.java`)
loads `classpath*:codegen-examples/**/*.{sql,py,yaml,yml}` at startup (`:53-61`)
and exposes, per blueprint key:
> `public List<Example> getExamplesForBlueprint(String blueprintKey)` (`:67-85`)
> — resolves `codegen_hints.example_keys` → `Example(key, language, content)`
> records (max 3, `MAX_EXAMPLES = 3` at `:33`).

**`CodegenExampleService` is NOT wired into the generator today.** Its only callers
are `chat/service/ChatToolExecutor.java` and `blueprint/controller/BlueprintController.java`
(verified: `grep -rln "CodegenExampleService" backend/src/main/java/com/pulse/`).
`CodeGenerationService` does not import or reference it. **Wiring it into codegen is
part of this task** (constructor-inject it into `CodeGenerationService`).

Example files relevant to B2S already exist:
- `backend/src/main/resources/codegen-examples/staging/stg_cleaning_basic.sql`
  (trim / rename / drop / partition core, with `__COLUMNS_TO_TRIM_LIST__`,
  `__RENAME_PAIRS__`, `__DROP_COLUMNS_LIST__` substitution markers).
- `backend/src/main/resources/codegen-examples/staging/stg_cleaning_type_cast.sql`
  (TRY_CAST typed projection + cast-failure diagnostics).

### C. The LLM call mechanism to REUSE (do not build a new one)

`backend/src/main/java/com/pulse/pipeline/service/SchemaInferenceService.java`
already makes OpenAI-compatible chat-completions calls over `HttpURLConnection`.
Reuse this exact mechanism and config:
- Config injection (`SchemaInferenceService.java:22-29`):
  > ```java
  > @Value("${pulse.schema-inference.api-key:${pulse.llm.api-key:}}")  private String apiKey;
  > @Value("${pulse.schema-inference.base-url:${OPENROUTER_BASE_URL:https://openrouter.ai/api/v1}}") private String baseUrl;
  > @Value("${pulse.schema-inference.model:${SCHEMA_INFERENCE_MODEL:google/gemini-2.0-flash-001}}") private String model;
  > ```
  For S3, ground the body-writer on the **primary** LLM config
  `pulse.llm.api-key` / `pulse.llm.base-url` / `pulse.llm.model`
  (`application.yml:88-91`: `model: openai/gpt-5.2`), since this is code generation,
  not schema inference. (Schema inference uses a cheaper model on purpose.)
- The call itself (`SchemaInferenceService.java:122-171`) — POST to
  `baseUrl + "/chat/completions"` with `Authorization: Bearer <apiKey>`,
  body `{model, messages:[{role:system},{role:user}], temperature, max_tokens}`,
  then read `choices[0].message.content`. Copy this shape.
- **Graceful no-key behavior is already the established pattern**
  (`SchemaInferenceService.java:56-59`):
  > `if (apiKey == null || apiKey.isBlank()) { log.debug("...skipped: no API key..."); return null; }`
  S3 must do the same: **no key → fall back to the current template body.**

There is **no shared LLM client class** — `SchemaInferenceService.callLLM` is
private. You may either (a) extract a small reusable client, or (b) implement an
equivalent private `callLLM` in a new codegen service. Either is acceptable; do not
add a new HTTP library or a new config key.

### D. Post-generation verification that already exists

`backend/src/main/java/com/pulse/codegen/scan/ForbiddenTokenScanner.java`:
- `ForbiddenTokenScanner.scan(artifacts)` already runs post-generation at
  `CodeGenerationService.java:219` and **already scans `DBT_MODEL` artifacts**
  (`DBT_MODEL` is in `SCANNABLE_FILE_TYPES`, `ForbiddenTokenScanner.java:90-100`).
  It flags raw secrets (PEM keys, GCP SA JSON, GitHub PATs) and internal PULSE
  tokens (`scanContent`, `:126-131`).
- `scanForPlaceholders(content)` (`:152-174`) is invoked **only** for
  `PYSPARK_JOB` / `AIRFLOW_DAG` (`CodeGenerationService.java:226-229`) and
  deliberately NOT for dbt models (Jinja vars look like placeholders).

**A schema/repair loop does NOT exist yet.** ADR 0002 says the LLM output is
*"checked afterward by a forbidden-token scan and a schema/repair loop"* — the
forbidden-token scan exists (above); **the schema check + bounded repair retry is
new and must be added by this task** (scoped to the generated body, see Task §4).

### E. Resolved input columns available to ground the LLM

Resolved per-port column schemas are persisted as `InstancePortSchema` rows
(`backend/src/main/java/com/pulse/pipeline/model/InstancePortSchema.java`):
`instanceId` + `portName` + `direction` + `schemaJson` (a JSON map whose `columns`
key is a list of `{name, type}`), produced by
`backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java`
(`getSchemaGraph(versionId)` at `:117`, reading
`InstancePortSchemaRepository.findByInstanceIdIn(...)` at `:123`). The same column
shape `{"columns":[{"name":..,"type":..}]}` is what `SchemaInferenceService`
consumes (`SchemaInferenceService.java:50-54`, `SYSTEM_PROMPT` output format
`:285-289`).

To ground the LLM you need the **input** columns for the transform's primary input
port. Fetch the `InstancePortSchema` for `inst.getId()` + the input port name +
`direction = "input"` (or, equivalently, the upstream instance's **output** port).
> **[verify]** Confirm whether `InstancePortSchema` rows are reliably populated at
> codegen time for the anchor path, or whether you must read the upstream's persisted
> output schema / the bronze `data-oracle` columns. The seam already has the upstream
> ref via `resolveUpstreamFromExpr`; pair that with the upstream instance's output
> `InstancePortSchema`. If no resolved schema is available, pass an empty column list
> and let the LLM emit a `SELECT *`-shaped body — do not block generation.

---

## Task — at the transform-body seam, write silver/gold bodies with the LLM

Implement ADR 0002 for **silver and gold dbt transform bodies only**.

1. **Wire `CodegenExampleService` into `CodeGenerationService`** (constructor
   injection). It is a Spring `@Service`; just add it as a dependency.

2. **At the seam (`CodeGenerationService.java:1668-1704`), for the in-scope
   blueprints, replace the template-produced `body` string with an LLM-written
   body.** In-scope = `TRANSFORM`/`MODELING` blueprints whose body is a
   per-blueprint SELECT (the `generate*SqlBody` branches). Start with
   **`BronzeToSilverCleaning`** (the anchor's silver step); the mechanism must be
   blueprint-agnostic so other transform blueprints can opt in, but you do not have
   to convert every branch in this lane.
   - Build the LLM **user prompt** from: `bp.getDescription()`; the example code
     (`codegenExampleService.getExamplesForBlueprint(bpKey)` → up to 3 example
     bodies); the **resolved input columns** (§E); `inst.getParams()`; the
     **target Mode** (GCP vs DPC — derive from the active runtime persona /
     `RuntimeAuthorityService`, consistent with how the rest of codegen resolves
     Mode; **[verify]** the exact accessor in this file); and the **deterministic
     skeleton facts the body must honor**: the upstream ref string from
     `resolveUpstreamFromExpr(...)` (the body MUST select FROM that), the target
     table/alias, the materialization, and the partition columns.
   - **System prompt** must instruct: output **only** a dbt SQL SELECT body (the
     `WITH ... SELECT ...` portion), no `{{ config(...) }}` header, no markdown
     fences, read FROM the provided upstream ref, do not invent table names or
     paths, do not emit secrets/credentials.
   - **DETERMINISM (operator requirement — required for the byte-exact test).** The
     generated SQL MUST be deterministic: **stable column ordering**; an **explicit
     tiebreaker** for any dedup / ranking (e.g. `ROW_NUMBER() OVER (… ORDER BY
     <unique business key>)`, never an unordered `DISTINCT ON`-style pick); and
     **no non-deterministic functions** (`current_timestamp`, `now`, `random`,
     `uuid`, …) in the body — those belong only to the skeleton's audit columns.
     The output table must be **byte-reproducible across runs** (ADR 0004). The
     LLM's *code* may vary run-to-run; its *data output* must not.
   - Use the LLM response **content** as the `body` string fed into the existing
     skeleton at `:1783-1789`. The config header, audit columns, incremental guard,
     and `createArtifact` all stay exactly as they are.

3. **Keep the skeleton deterministic.** Do **not** let the LLM choose: file paths,
   table DDL, `materialized`, `file_format`, `location_root`, `partition_by`,
   `unique_key`, tags, alias, `{{ source(...) }}` / `{{ ref(...) }}` resolution, or
   Mode. Those remain computed in Java exactly as at `:1706-1782`.

4. **Verify the LLM body with a bounded repair retry.** After the LLM returns a
   body, BEFORE feeding it into the skeleton:
   - **Forbidden-token check:** run `ForbiddenTokenScanner.scanContent(body)`
     (`ForbiddenTokenScanner.java:126`). If it returns any violation, the body is
     rejected.
   - **Schema/shape check:** validate the body is a usable dbt SELECT — at minimum:
     non-empty, contains a `SELECT`, contains the upstream ref string returned by
     `resolveUpstreamFromExpr` (so the body actually reads the wired source), and
     contains no `{{ config` (the skeleton owns config). Optionally check it
     references only column names from the resolved input schema (where available).
   - **Bounded repair:** on failure, re-prompt the LLM **at most once or twice**
     (hard cap — e.g. 2 total attempts) with the violation appended to the prompt.
     If it still fails, **fall back to the current template body** (call the
     existing `generate*SqlBody(...)` — keep those methods) and log a warning. The
     run must still complete.

5. **Behind a flag / graceful path.** If `pulse.llm.api-key` is blank/missing
   (or an opt-out flag is set), skip the LLM entirely and use the existing template
   body — mirroring `SchemaInferenceService.java:56-59`. Non-LLM tests and CI
   (which have no live key) must continue to pass and emit the deterministic
   template output.

---

## Constraints

- **Skeleton stays deterministic.** No LLM involvement in paths, DDL,
  materialization, `file_format`, `location_root`, partitioning, `unique_key`,
  tags, alias, source/ref resolution, audit columns, or Mode selection. Those are
  fixed by `CodeGenerationService.java:1706-1791` and must keep producing identical
  output for a given input.
- **Reuse the existing LLM config + mechanism.** Use `pulse.llm.*`
  (`application.yml:88-91`) and the `HttpURLConnection` chat-completions pattern
  from `SchemaInferenceService.java:122-171`. **No new config keys, no new HTTP
  client library, no new LLM provider.**
- **Graceful degradation.** Missing/blank API key → current template body, run
  succeeds. This is required so the non-LLM test suite and CI stay green.
- **Do not break ingestion / bronze.** Ingestion (`INGESTION` category) and the
  PySpark bronze path (`generatePySparkJobs`) are out of scope — do not touch them.
  Only `TRANSFORM`/`MODELING` dbt bodies change.
- **Secrets are SecretRefs only.** The LLM body must never contain credentials; the
  forbidden-token scan (`ForbiddenTokenScanner`) must pass on every generated
  artifact (it already runs at `CodeGenerationService.java:219`).
- **Bounded LLM cost.** Hard cap total LLM attempts per body (≤ 2 incl. repair);
  set a sane `max_tokens` and `temperature` (low — this is codegen, not creative
  writing).

---

## Worktree & handoff (ADR 0008)

- **Isolation.** Work in a dedicated git worktree at
  `/Users/aameradam/projects/dev/pulse-s3` on branch **`s3-llm-builder`** (a peer
  folder, never the shared working tree). Create it with:
  `git worktree add /Users/aameradam/projects/dev/pulse-s3 -b s3-llm-builder`.
- **Evidence doc (your CLAIM).** Before claiming done, write
  `docs/evidence/S3-EVIDENCE.md` in your branch containing:
  - every file changed + a one-line why;
  - the **exact** build/compile command run and its result — at minimum
    `cd backend && ./gradlew build` (or `./gradlew test`), pasted verbatim with the
    pass/fail tail;
  - any local checks you ran on the generated output (e.g. you ran the Builder for
    the anchor pipeline with no API key and confirmed the deterministic template
    body still emits; and, if you had a key, what the LLM body looked like);
  - exact reproduction steps (how to regenerate, where the dbt artifact lands).
- **Independent evaluator.** Do **not** self-certify. A fresh Claude evaluator
  agent will check out `s3-llm-builder`, re-run the behavioral test against sample
  data with its own eyes, and return PASS/FAIL. **Nothing merges until the
  evaluator passes it** (ADR 0008 §4–5). The evaluator does not trust
  `S3-EVIDENCE.md` — it re-runs everything.

---

## Behavioral test (evaluator-only — do NOT show the implementer)

**Goal:** prove the Builder now writes a **blueprint-specific, LLM-authored** silver
transform body — not the static `SELECT * FROM <ref>` template — and that the body
runs and produces a correct silver table.

**Setup:** the anchor bronze table `loan_master` (500 rows, 78 columns; oracle at
`backend/src/test/resources/e2e/oracle/loan_master/data-oracle.json`) feeding a
`BronzeToSilverCleaning` instance, with a live `pulse.llm.api-key`.

**Assert (two layers):**
1. **It is LLM-authored, not the template.** Inspect the generated dbt SQL body.
   The PRE-S3 template body for B2S is exactly `SELECT *\nFROM <upstream_ref>`
   (`CodeGenerationService.java:2347`). PASS requires the generated body to be
   **blueprint-specific cleaning SQL** — i.e. it reflects the B2S contract
   (type casts / trims / null handling / optional dedup), references real
   `loan_master` columns, and is materially richer than the bare passthrough. A
   bare `SELECT *` body is a FAIL (the LLM did not engage or the seam wasn't hit).
2. **It runs and the silver table is byte-exact correct — DETERMINISTIC.** The LLM's
   *code* may vary; its *data output must not*. Acceptance:
   - A **deterministic reference oracle** (the exact expected silver table) is authored
     by the spec authors — a reference computation applying the **locked** cleaning
     params to `loan_master`, independent of the LLM. The generated SQL's output must
     match it **byte-for-byte**.
   - **Run 2–3× with separate LLM generations.** Generate the body 2–3 times (separate
     LLM calls → possibly different SQL), run each, and require every output to be
     **byte-identical to each other AND to the reference oracle.** Any divergence is a
     **FAIL = a real bug** (ambiguous transform, non-deterministic SQL such as a dedup
     with no tiebreaker, or wrong logic) — never tolerated, never loosened.

**RESOLVED (operator, 2026-06-13): byte-exact deterministic oracle — NOT a fuzzy
shape/rules check.** If the data output varies, different developers' pipelines would
produce different results — for a data platform that is a defect, not acceptable LLM
non-determinism. Therefore:
- The bronze/ingestion oracle (`.../oracle/loan_master/data-oracle.json`, 500×78) is
  NOT the silver target — a **loan_master silver answer key must be authored** as a
  deterministic reference (apply the locked `type_coercions` / `columns_to_trim` /
  `null_handling` / `dedup_key` / `rename_map` / `drop_columns` to the bronze data,
  with a **deterministic dedup tiebreaker** so the result is reproducible).
- The generic `contacts` B2S oracle is a useful *pattern* but is not loan_master.
- If a byte-exact silver CSV cannot be produced, that is a defect to fix (ambiguous
  cleaning spec or broken codegen) — not a reason to weaken the test.

**Note on B2S ports/params (context for the evaluator):** the B2S blueprint declares
input port **`raw_input`** and output port **`cleaned_output`**
(`V7__blueprints_catalog.sql:116-117`), and the generator reads the `raw_input` port
(`CodeGenerationService.java:2346`) — these are correct for a bronze→silver step. The
**current** (latest-winning) params are set by `V93__params_schema_audit_phase_b.sql:363-409`:
`type_coercions` (map of column→type casts), `columns_to_trim`, `rename_map`,
`null_handling` (keep/coerce_to/drop_row), `dedup_key`, `drop_columns`,
`partition_by` (default `['ds']`). (V7's original params_schema at `:115` is the
older, thinner version superseded by V93.) These params are the cleaning contract the
LLM body must honor and the acceptance must check.
