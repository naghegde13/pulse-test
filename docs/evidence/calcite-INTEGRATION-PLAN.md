# CALCITE LANE — INTEGRATION PLAN (ready-to-apply, deferred SERIAL JOINS)

> **What this is.** The exact, ready-to-apply edits for the three Calcite-lane integration
> points that touch files owned by the **Builder lane** (`SchemaPropagationService.java`,
> `CodeGenerationService.java`). These are **deferred serial joins**: the orchestrator applies
> them **post-Builder-merge**, by grepping the named anchors below. **Line numbers drift —
> anchor on method/string names.** Verified against the worktree
> `/Users/aameradam/projects/dev/pulse-wt/calcite` on 2026-06-16.
>
> Implements `docs/build-specs/SPEC-calcite-sql-model.md` (#6) per `docs/adr/0024-…md`.
> Cites ADR 0011 (deterministic schema, zero LLM — unknown → loud fail), ADR 0009 (byte-exact),
> ADR 0012 (closed 32-op vocab), ADR 0013 (deterministic Builder; LLM Chat-side only).
>
> **Validator (already built by this lane, NOT a deferred edit):**
> `com.pulse.expression.service.CalciteSqlModelValidator` —
> `resolveSqlModelSchema(steps, inputColumns, declaredOutputSchema)`.
>
> **LOCKED 2026-06-16:** Calcite is **`sql-model`-ONLY**. `SourceSQL` validates via the **source**
> (JDBC prepare/metadata, §C.2), NOT Calcite.

---

## ANCHOR LEDGER (all grep-verified in the worktree, 2026-06-16)

`SchemaPropagationService.java`
(`backend/src/main/java/com/pulse/pipeline/service/SchemaPropagationService.java`):

| anchor (grep by name) | verified signature / text | ~line |
|---|---|---|
| `deriveBaseOutputSchema(...)` + `switch (key`  | `private Map<String, Object> deriveBaseOutputSchema(SubPipelineInstance inst, Blueprint bp, String outputPort, Map<String, Object> primary, Map<String, Object> secondary, String key, Map<String, Object> params)` | 811 |
| the OLD blueprint-key `switch (key == null ? "" : key)` | `switch (key == null ? "" : key) {` | 814 |
| `key.endsWith("Ingestion")` route | `if (key != null && key.endsWith("Ingestion")) { return ingestionSchema(inst); }` | 840–841 |
| `default ->` LLM leak | `schemaInferenceService.inferOutputSchema(key, primary, secondary, params)` | 843–851 |
| `ingestionSchema(SubPipelineInstance inst)` | `private Map<String, Object> ingestionSchema(SubPipelineInstance inst)` | 861 |
| audit-column append inside `ingestionSchema` | `cols.addAll(IngestionAuditColumns.asColumnDescriptors());` | 881 |
| field `schemaInferenceService` | `private final SchemaInferenceService schemaInferenceService;` | 72 |
| `wrapColumns(...)` | `private Map<String, Object> wrapColumns(List<Map<String, Object>> cols)` | 1205 |
| `extractColumns(...)` | `private List<Map<String, Object>> extractColumns(Map<String, Object> schema)` | 1192 |
| `column(String,String)` | `private Map<String, Object> column(String name, String type)` | 1211 |
| `passThrough(...)` | `private Map<String, Object> passThrough(Map<String, Object> primary)` | 1180 |
| `tryResolveDatasetSchema(inst)` | `private Map<String, Object> tryResolveDatasetSchema(SubPipelineInstance inst)` | 459 |
| `appendColumns(...)` (exists, used by SCD2/Snapshot) | `private Map<String, Object> appendColumns(Map<String, Object> primary, List<Map<String, Object>> extras)` | 1185 |
| `inst.getParams()` / `inst.getOutputSchema()` | `SubPipelineInstance` getters (model.SubPipelineInstance) | — |

`IngestionAuditColumns.java`
(`backend/src/main/java/com/pulse/codegen/audit/IngestionAuditColumns.java`):

| anchor | verified | ~line |
|---|---|---|
| `asColumnDescriptors()` | `public static List<Map<String, Object>> asColumnDescriptors()` — returns 7 cols (`_pulse_ingested_at`,`_pulse_processing_ts`,`_pulse_pipeline`,`_pulse_task`,`_pulse_run_id`,`_pulse_source_uri`,`_pulse_business_date`), each with name/type/nullable=false/description/lineage="injected:audit"/tags=["audit"] | 59 |

`CodeGenerationService.java`
(`backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java`):

| anchor (grep by string) | verified text | ~line |
|---|---|---|
| Spark `env_vars` | `env_vars={'PULSE_BUSINESS_DATE': '{{ ds }}', 'PULSE_PROCESSING_TS': '{{ ts }}'},` | 591 |
| dbt `bash_command` (NO `--vars` today) | `bash_command='cd /opt/dbt && dbt build --select tag:%s,tag:%s',` then `String.format(..., slug, taskSlug)` | 597–598 |
| dbt BashOperator `env` | `env={'PULSE_BUSINESS_DATE': '{{ ds }}', 'PULSE_PROCESSING_TS': '{{ ts }}'},` | 599 |
| GX PythonOperator `env` | `env={**__import__('os').environ, 'PULSE_BUSINESS_DATE': ctx['ds'], 'PULSE_PROCESSING_TS': ctx['ts']}),` | 607 |
| `pulse_business_date` var convention | `firstNonBlankString(params, "business_date_var", "snapshot_date_var")` → default `"pulse_business_date"` | 2174–2177 |
| `slug` / `taskSlug` in scope of the dbt anchor | `String slug = slugify(pipeline.getName());` (181); `String taskSlug = slugify(inst.getName());` (476) | 181 / 476 |
| `firstNonBlankString(...)` | `private String firstNonBlankString(Map<String, Object> params, String... keys)` | 1932 |
| dbt model SQL assembly | `generateDbtModels(...)` (1584) + per-blueprint `generate*SqlBody(inst, slug)` (1670–1683) | 1584+ |
| `pulse_dates` runtime helper already emitted | `createArtifact(run, "pulse_dates/__init__.py", "RUNTIME_SUPPORT", …)` | ~3720 |
| existing connector resolution (precedent for part b) | `resolveCredentialProfile(...)` `credentialProfileRepo.findByConnectorInstanceIdAndEnvironment(connectorInstanceId,"dev")` (1400–1407); `resolveConnectorInstance(...)` `connectorInstanceRepo.findById(...)` (1419–1423) | 1400 / 1419 |

`DateMnemonic.java` (`backend/src/main/java/com/pulse/common/text/DateMnemonic.java`):

| anchor | verified | ~line |
|---|---|---|
| `validateOrThrow(String)` | `public static void validateOrThrow(String token)` — throws `IllegalArgumentException("Invalid date input: …")` on a bad token | 105 |
| `isMnemonic(String)` | `public static boolean isMnemonic(String token)` | 77 |
| `isValid(String)` | `public static boolean isValid(String token)` | 57 |
| 3-way-sync javadoc | "Vocabulary kept in sync with `pulse_dates/__init__.py`… MUST be matched in the Python resolver and … `PulseSystemPrompt`." | 19–21 |

`ForbiddenTokenScanner.java` (`backend/src/main/java/com/pulse/codegen/scan/ForbiddenTokenScanner.java`):

| anchor | verified | ~line |
|---|---|---|
| `PLACEHOLDER_TOKENS` set | `private static final Set<String> PLACEHOLDER_TOKENS = Set.of("${JDBC_URL}", …)` | 52–67 |
| generic `${VAR}` regex | `Pattern.compile("\\$\\{[A-Z][A-Z0-9_]*}")` | 161 |
| `INTERNAL_TOKENS` set | `private static final Set<String> INTERNAL_TOKENS = Set.of("PULSE_AIRFLOW_CALLBACK_URL", …)` | 71–84 |
| scans `[[ ]]`? | **NO** — verified: no `[[`/`]]` pattern anywhere in the scanner | — |
| scan entrypoints | `scan(List<GeneratedArtifact>)` (106), `scanContent(String)` (126), `scanForPlaceholders(String)` (152) | 106+ |

**Anchors that did NOT match (and what was used instead):**
- `inst.getOpConfig()` / a `read-source` op-config accessor on `SubPipelineInstance` — **NOT FOUND** as
  a dedicated method. `SourceSQL`'s `source_query` + `connector_instance_id` live in **`inst.getParams()`**
  (the blueprint param surface; the V153 op-list maps params into the `read-source` op `config` via param-refs
  per #6 §B.1). The branch reads them from `inst.getParams()` (and, under the op-walker, from the resolved
  op-entry `config`, which is param-ref-resolved to the same values). This matches how every other branch reads
  config today (`Map<String,Object> params = inst.getParams()`, line 800).
- A JDBC `Connection`/`DataSource` resolver on `SchemaPropagationService` — **NOT FOUND**:
  `SchemaPropagationService`'s constructor (lines 75–82) injects `instanceRepo, wiringRepo, blueprintRepo,
  portSchemaRepo, conflictRepo, datasetRepo, schemaInferenceService, objectMapper` only — **no**
  `credentialProfileRepo`/`connectorInstanceRepo` and **no** DataSource. The connector→DataSource resolution
  is therefore a genuine new collaborator (TODO-anchor in part b), with the existing
  `CodeGenerationService.resolveConnectorInstance/resolveCredentialProfile` (lines 1400/1419) as the precedent
  for **what** to call.
- A live `--vars` usage in `CodeGenerationService` — **NOT FOUND** (confirmed the dbt invocation carries none
  today; part c adds the first one).

---

## (a) rule-27 `sql-model` resolution call-site (op-walker rule body)

> **Owner of the call site:** the **Builder lane's op-walker** (the deterministic op-list-walker, keyed on OP
> name, that **replaces** the `SchemaPropagationService:814` `switch (key)`). This is **NOT** a new `case` in that
> switch. The Calcite lane **delivers** the rule body below; the Builder lane wires it into the walker.
> **Validator already built by the Calcite lane:** `com.pulse.expression.service.CalciteSqlModelValidator`.

**Where it goes:** in the op-walker, the branch that fires when the walked op-entry's `op == "sql-model"`
(#1 §B rule 27). It is the **OUT-schema producer** for that op-entry.

**Rule body (ready to drop into the walker's rule-27 handler):**

```java
// rule 27 — sql-model OUT schema. Calcite-derived (primary), declared-schema fallback (§A.6).
// Owner: op-walker (Builder lane). Validator: Calcite lane. NEVER reaches schemaInferenceService (ADR 0011).
case "sql-model" -> {
    // op config (param-ref-resolved by the walker):
    //   steps                  -> List<SqlStep> { name, sql, materialize }
    //   declared_output_schema -> optional List<Map<String,Object>> (#1 §B.0 column-descriptor list, §A.6)
    List<CalciteSqlModelValidator.SqlStep> steps = readSqlSteps(opConfig.get("steps"));
    List<Map<String, Object>> inputColumns = extractColumns(primary); // the 'input'-port accumulated schema
    List<Map<String, Object>> declared     = readDeclaredOutputSchema(opConfig.get("declared_output_schema"));

    // Single call into the Calcite lane's validator. It does NOT execute SQL and does NOT resolve a date (§A.1).
    // On a Calcite parse/validation error WITH a declared schema present, the validator returns the declared
    // schema and logs a warning; on error WITHOUT a declared schema it raises a BuildFailure (loud-fail, §A.6).
    List<Map<String, Object>> outColumns =
            calciteSqlModelValidator.resolveSqlModelSchema(steps, inputColumns, declared);

    return wrapColumns(outColumns);   // OUT schema in the #1 §B.0 encoding (line 1205)
}
```

- **Signature contract (already shipped by the Calcite lane):**
  `List<Map<String,Object>> CalciteSqlModelValidator.resolveSqlModelSchema(List<SqlStep> steps,
  List<Map<String,Object>> inputColumns, List<Map<String,Object>> declaredOutputSchema)`.
  `SqlStep` is the record `{ String name, String sql, boolean materialize }`. `inputColumns` /
  `declaredOutputSchema` use the #1 §B.0 `{name,type,nullable}` (+ nested `fields`/`element`) encoding.
  The returned list is the terminal `SELECT`'s derived columns (§A.4). `declaredOutputSchema` may be `null`.
- **OUT becomes the op-entry's OUT schema** by wrapping through the existing helper `wrapColumns(...)`
  (`SchemaPropagationService:1205`) — identical to every other branch's return shape.
- **ADR-0011 assertion (load-bearing):** this path resolves via **Calcite → declared-schema → loud-fail
  ONLY**. It **NEVER** reaches `default -> schemaInferenceService.inferOutputSchema(...)`
  (`SchemaPropagationService:843–851`). A test must assert `sql-model` resolution never invokes
  `schemaInferenceService` (mirror of the SourceSQL assertion in part b).
- `readSqlSteps(...)` / `readDeclaredOutputSchema(...)` are trivial deserializers (config `Object` →
  `List<SqlStep>` / `List<Map<String,Object>>`); the Builder lane authors them next to the walker (they are
  not in `SchemaPropagationService` today).

**INTERIM (only if the op-walker is not yet landed when this rule ships):** add a **temporary** `case "SqlModel"`
to the `:814` `switch (key)` that calls the **same** validator, then returns `wrapColumns(...)`:

```java
// INTERIM — DELETE once the op-walker lands. Schema-effect then lives in the op-keyed rule-27, not this switch.
case "SqlModel" -> {
    List<CalciteSqlModelValidator.SqlStep> steps = readSqlSteps(params.get("steps"));
    List<Map<String, Object>> declared = readDeclaredOutputSchema(params.get("declared_output_schema"));
    return wrapColumns(
        calciteSqlModelValidator.resolveSqlModelSchema(steps, extractColumns(primary), declared));
}
```
This interim `case` is **deleted** the moment the walker is in place. (It also requires injecting
`CalciteSqlModelValidator` into `SchemaPropagationService`'s constructor for the interim window — remove on
deletion.) Either way the ADR-0011 never-LLM assertion gates it.

---

## (b) NEW `SourceSQL` `source_query` "source-prepare" branch inside `ingestionSchema()`

> **CREATE** task (§E note / G-8). `ingestionSchema()` today derives bronze = source dataset schema +
> `IngestionAuditColumns` (`SchemaPropagationService:861–883`). The `source_query` path does **NOT** exist.
> This branch adds it. It **does NOT call Calcite** (LOCKED 2026-06-16 — Calcite is `sql-model`-only).

**Where it goes:** at the **top** of `ingestionSchema(SubPipelineInstance inst)` (anchor: method name
`ingestionSchema`, ~861) — before the existing dataset-schema path, short-circuiting when `source_query` is
present. The existing audit-append (`cols.addAll(IngestionAuditColumns.asColumnDescriptors())`, ~881) is reused
**verbatim** by the new branch.

**Ready-to-apply branch (insert at the start of `ingestionSchema`):**

```java
private Map<String, Object> ingestionSchema(SubPipelineInstance inst) {
    Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();

    // ---- NEW: SourceSQL source-prepare branch (§C.2). NOT Calcite. ----
    Object sourceQueryObj = params.get("source_query");
    Object connectorIdObj = params.get("connector_instance_id");  // SINK convention — NOT "connector_id"
    if (sourceQueryObj instanceof String sourceQuery && !sourceQuery.isBlank()
            && connectorIdObj instanceof String connectorInstanceId && !connectorInstanceId.isBlank()) {

        // (1) Substitute every [[ ... ]] mnemonic -> a dummy DATE literal BEFORE prepare, so the source parses
        //     a well-formed query. The value is NEVER resolved at design time (§C.2 / §D).
        String preparable = MNEMONIC_TOKEN.matcher(sourceQuery).replaceAll("DATE '1970-01-01'");
        //     MNEMONIC_TOKEN == Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]")  (declared once on the class)

        try {
            // (2) Resolve the JDBC connector + credential profile (SecretRefs) from the Producer Registry.
            //     OWNED BY whoever owns credential/connector resolution — NOT this lane. Must return a live
            //     JDBC Connection/DataSource for connectorInstanceId in env "dev" (dev-builder only).
            //     Precedent to mirror: CodeGenerationService.resolveConnectorInstance(params) /
            //     resolveCredentialProfile(params) -> connectorInstanceRepo.findById /
            //     credentialProfileRepo.findByConnectorInstanceIdAndEnvironment(connectorInstanceId, "dev").
            try (Connection conn =
                     /* TODO-ANCHOR: resolve DataSource from connector_instance_id via
                        <ProducerRegistry/connector service>; returns a JDBC Connection (dev env, SecretRefs) */
                     connectorJdbcResolver.openConnection(connectorInstanceId, "dev")) {

                // (3) No-rows round-trip — get metadata WITHOUT executing for data.
                String wrapped = "SELECT * FROM (" + preparable + ") pulse_meta WHERE 1=0";  // or append LIMIT 0
                try (PreparedStatement ps = conn.prepareStatement(wrapped)) {
                    ResultSetMetaData md = ps.getMetaData();                 // metadata only, no execution
                    int n = md.getColumnCount();
                    List<Map<String, Object>> cols = new ArrayList<>();
                    for (int i = 1; i <= n; i++) {
                        // (4) Map java.sql.Types -> PULSE type per the §C.2 table (below).
                        String pulseType = jdbcTypeToPulse(md.getColumnType(i));   // loud-fail on unmapped
                        boolean nullable = md.isNullable(i) == ResultSetMetaData.columnNullable;
                        // Build the column as a LinkedHashMap (#1 §B.0 encoding: name/type/nullable, ordered).
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", md.getColumnLabel(i));   // alias if present, else column name
                        col.put("type", pulseType);
                        col.put("nullable", nullable);
                        col.put("lineage", "source");            // (5) tag source-derived columns
                        cols.add(col);
                    }
                    // (5) Append the canonical audit columns the SAME way the existing path does (~:881).
                    cols.addAll(IngestionAuditColumns.asColumnDescriptors());
                    return wrapColumns(cols);
                }
            }
        } catch (SQLException | RuntimeException e) {
            // (6) Source unreachable / prepare error at design time -> §A.6 declare-schema fallback.
            List<Map<String, Object>> declared = readDeclaredOutputSchema(params.get("declared_output_schema"));
            if (declared != null && !declared.isEmpty()) {
                log.warn("SourceSQL source-prepare failed for instance {}; using declared_output_schema. cause={}",
                        inst.getId(), e.getMessage());
                List<Map<String, Object>> cols = new ArrayList<>(declared);
                cols.addAll(IngestionAuditColumns.asColumnDescriptors());
                return wrapColumns(cols);
            }
            throw new IllegalStateException(
                "SourceSQL: source unreachable at design time and no declared_output_schema present "
                + "(ADR 0011 loud-fail). connector_instance_id=" + connectorInstanceId, e);  // loud-fail
        }
    }
    // ---- END NEW branch. Existing dataset-schema ingestion path continues below, unchanged. ----

    List<Map<String, Object>> cols = new ArrayList<>();
    // ... existing body (tryResolveDatasetSchema, lineage="source" tagging, audit append at ~:881) ...
}
```

**§C.2 JDBC → PULSE type map** (reproduced in full; `jdbcTypeToPulse(int)` switch on `java.sql.Types`;
**unmapped → loud build-fail**, ADR 0011):

| `java.sql.Types` | PULSE type |
|---|---|
| `CHAR`, `VARCHAR`, `LONGVARCHAR`, `NCHAR`, `NVARCHAR`, `CLOB` | `string` |
| `TINYINT`, `SMALLINT`, `INTEGER` | `integer` |
| `BIGINT` | `long` |
| `REAL`, `FLOAT`, `DOUBLE` | `double` |
| `DECIMAL`, `NUMERIC` | `decimal` |
| `BIT`, `BOOLEAN` | `boolean` |
| `DATE` | `date` |
| `TIMESTAMP`, `TIMESTAMP_WITH_TIMEZONE` | `timestamp` |
| `ARRAY` | `list` (element recursively mapped) |
| `STRUCT` | `struct` (fields recursively mapped) |
| `TIME` | `string` *(GUESS default — PULSE has no time-of-day type)* |
| **anything else (unmapped vendor/source type)** | **loud build-fail** (DE then uses §A.6 declared fallback) |

```java
private static String jdbcTypeToPulse(int t) {
    return switch (t) {
        case Types.CHAR, Types.VARCHAR, Types.LONGVARCHAR,
             Types.NCHAR, Types.NVARCHAR, Types.CLOB                 -> "string";
        case Types.TINYINT, Types.SMALLINT, Types.INTEGER           -> "integer";
        case Types.BIGINT                                           -> "long";
        case Types.REAL, Types.FLOAT, Types.DOUBLE                  -> "double";
        case Types.DECIMAL, Types.NUMERIC                           -> "decimal";
        case Types.BIT, Types.BOOLEAN                               -> "boolean";
        case Types.DATE                                             -> "date";
        case Types.TIMESTAMP, Types.TIMESTAMP_WITH_TIMEZONE         -> "timestamp";
        case Types.ARRAY                                            -> "list";    // element recursively mapped
        case Types.STRUCT                                           -> "struct";  // fields recursively mapped
        case Types.TIME                                             -> "string";  // GUESS default
        default -> throw new IllegalStateException(
            "SourceSQL: unmapped JDBC type " + t + " (ADR 0011 loud-fail; use declared_output_schema)");
    };
}
```

**Notes on this branch:**
- **(1)** mnemonic→dummy-DATE uses the SAME class-level `Pattern` as part (c) (`MNEMONIC_TOKEN =
  Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]")`). The value is **never resolved at design time**.
- **(2)** the connector→`Connection`/`DataSource` resolution is the **only** part this lane does not own —
  flagged `TODO-ANCHOR`. Whoever owns credential/connector resolution supplies `connectorJdbcResolver`
  (a new collaborator injected into `SchemaPropagationService`; the constructor at lines 75–82 has **no**
  connector repos today). It must return a **live JDBC `Connection` (or `DataSource`)** for the
  `connector_instance_id` in env `"dev"`, using the SecretRefs from the credential profile — mirroring
  `CodeGenerationService.resolveCredentialProfile` (1400) / `resolveConnectorInstance` (1419).
- **(3)** `ps.getMetaData()` returns `ResultSetMetaData` **without executing** the query for data; the
  `WHERE 1=0` (or `LIMIT 0`) wrapper guarantees a no-rows round-trip even on drivers that need to plan.
- **(4)** each column is a `LinkedHashMap` with **name/type/nullable** in the #1 §B.0 encoding;
  `nullable = md.isNullable(i) == ResultSetMetaData.columnNullable`.
- **(5)** columns tagged `lineage="source"`; audit columns appended via the **existing**
  `IngestionAuditColumns.asColumnDescriptors()` path (identical to `:881`).
- **(6)** on `SQLException`/source-unreachable → §A.6: declared schema if present (warn), else **loud-fail**.
- **EXPLICIT:** this branch **does NOT call Calcite** — `SourceSQL` validates via the source only.

**ROUTING fix (ADR-0011-critical):** `SourceSQL` does **NOT** end in `"Ingestion"`, so the live
`key.endsWith("Ingestion")` route (`SchemaPropagationService:840–841`) **misses** it and it would fall through
to `default -> schemaInferenceService.inferOutputSchema(...)` (`:843–851`) — an LLM leak. Resolution:
- **Under the op-walker (target state):** `SourceSQL`'s first op is `read-source` carrying `source_query` +
  `connector_instance_id` in its config, so it resolves **op-keyed** through this `source_query` branch (rule
  24 / `read-source`), never through any blueprint-key fallback.
- **INTERIM (only if the walker is not yet landed):** add an **explicit** `SourceSQL` route into this branch
  in the live switch — do **NOT** rely on `endsWith("Ingestion")`. E.g. before the `default ->`:
  ```java
  case "SourceSQL" -> { return ingestionSchema(inst); }  // INTERIM — explicit route; delete when walker lands
  ```
  (The `source_query` short-circuit inside `ingestionSchema` then takes over.)
- **TEST (gates both target + interim):** assert `SourceSQL` resolves via the `read-source`/`source_query`
  branch and **NEVER** invokes `schemaInferenceService` (the symmetric ADR-0011 assertion to part a).

---

## (c) `[[ ]]` lowering + dbt `--vars` in `CodeGenerationService`

> Reuses `DateMnemonic` (`validateOrThrow`/`isMnemonic`/`isValid`) and the `pulse_dates` runtime resolver
> **UNCHANGED** (no fork of the 3-way-synced vocabulary). Adds only the embedded-token scan + per-engine
> lowering. After lowering, **no `[[ ]]` survives** → `ForbiddenTokenScanner` stays green.

### c.1 — Config-time scan (§D.2) — loud-fail typos

For **every SQL surface** — each `sql-model` step's `sql`, `SourceSQL.source_query`, and every SQL-expression
param (DERIVE `derived_columns[].expression`, `having_clause`, etc.) — extract each `[[ ... ]]` and validate the
inner mnemonic via `DateMnemonic.validateOrThrow(...)` (throws `IllegalArgumentException` on typo, ~`:105`):

```java
private static final Pattern MNEMONIC_TOKEN = Pattern.compile("\\[\\[\\s*(.*?)\\s*\\]\\]");

private void validateMnemonics(String sqlSurface) {
    Matcher m = MNEMONIC_TOKEN.matcher(sqlSurface);
    while (m.find()) {
        DateMnemonic.validateOrThrow(m.group(1).trim());   // loud-fail on unknown/typo mnemonic
    }
}
```

### c.2 — The SLUG rule (6-G07, deterministic)

Inner mnemonic text → **lowercase**, **every non-alphanumeric char → `_`**, **prefix `pulse_`**. Dedup by the
**normalized slug** (one var per distinct mnemonic — two `[[ PBD-1 ]]` → one var).

| mnemonic | slug | dbt var |
|---|---|---|
| `PBD-1` | `pbd_1` | `pulse_pbd_1` |
| `RUN_DATE` | `run_date` | `pulse_run_date` |
| `NBDOM(2)` | `nbdom_2_` | `pulse_nbdom_2_` |
| `SAME_DAY_LAST_WEEK` | `same_day_last_week` | `pulse_same_day_last_week` |

```java
/** Deterministic slug (6-G07): lowercase + non-alphanumeric -> '_', prefixed 'pulse_'. */
private static String mnemonicSlug(String mnemonic) {
    String norm = mnemonic.trim().toLowerCase(java.util.Locale.ROOT)
                          .replaceAll("[^a-z0-9]", "_");
    return "pulse_" + norm;
}
// Dedup: collect into a LinkedHashMap<slug, mnemonic> keyed by mnemonicSlug(m) — one entry per distinct slug.
```

### c.3 — dbt path: rewrite + `--vars` on the invocation (anchor `:597`)

1. In the dbt **model SQL** (assembled in `generateDbtModels(...)` / the `generate*SqlBody(...)` methods,
   ~`:1584`/`:1670–1683`), rewrite each `[[ m ]]` → `{{ var('pulse_<slug>') }}`:
   ```java
   String lowered = MNEMONIC_TOKEN.matcher(modelSql)
       .replaceAll(mr -> Matcher.quoteReplacement("{{ var('" + mnemonicSlug(mr.group(1).trim()) + "') }}"));
   ```
2. Append `--vars '{"pulse_<slug>":"<resolved>"}'` to the dbt invocation. **Anchor by string**
   `dbt build --select tag:` (~`:597–598`). New `bash_command` (one var per distinct mnemonic in the model):
   ```java
   // BEFORE (verified ~:597):
   //   bash_command='cd /opt/dbt && dbt build --select tag:%s,tag:%s',  String.format(..., slug, taskSlug)
   // AFTER — append --vars (varsJson = {"pulse_<slug>":"{{ <resolved-template> >}}", ...} for this task's mnemonics):
   dag.append(String.format(
       "            bash_command='cd /opt/dbt && dbt build --select tag:%s,tag:%s --vars '\"'\"'%s'\"'\"'',\n",
       slug, taskSlug, varsJson));
   // varsJson is built deterministically from the deduped slug set; when the task has NO mnemonics, omit --vars
   // entirely (emit the original bash_command unchanged) so no-mnemonic tasks are byte-identical to today (ADR 0009).
   ```
3. Emit a **runtime resolve step** that resolves each `m` (via `pulse_dates.resolve_mnemonic`, §c.5) and injects
   the `pulse_<slug>` var, **mirroring the existing `pulse_business_date` mechanism** at `:2174–2177`
   (`firstNonBlankString(params,"business_date_var","snapshot_date_var")` → default `"pulse_business_date"`).
   The `pulse_dates` runtime helper is already emitted as a `RUNTIME_SUPPORT` artifact (~`:3720`) — reuse it.

### c.4 — PySpark / SourceSQL path

No var name needed — the runtime resolve step resolves `m` and **string-substitutes the resolved date
directly** into the SQL (PySpark f-string / SourceSQL query body) before execution.

### c.5 — Runtime resolve call (§D.5) — Airflow context + pinned calendar bundle ONLY (never the PULSE DB)

```python
pulse_dates.resolve_mnemonic(
    m,
    as_of=PULSE_BUSINESS_DATE,            # = Airflow {{ ds }}; injected per operator:
                                          #   Spark   env_vars={'PULSE_BUSINESS_DATE':'{{ ds }}', ...}  (:591)
                                          #   dbt     env={'PULSE_BUSINESS_DATE':'{{ ds }}', ...}        (:599)
                                          #   GX      env={**os.environ, 'PULSE_BUSINESS_DATE':ctx['ds']}(:607)
    calendar_bundle_uri=...,              # pinned hash-bundle (never the PULSE DB)
    calendar_bundle_hash=...,
    calendar_id=...,                      # default US-FED
    previous_run_date=...,                # Airflow previous run (for PREVIOUS_RUN_DATE family) — resolves N-4
    fiscal_offset_months=...,             # from domain.business_date_config (fiscal mnemonics) — resolves N-4
)
```

### c.6 — Scanner assertion (load-bearing)

After lowering, **NO `[[ ]]` survives** into any emitted artifact: dbt → `{{ var(...) }}`, PySpark/SourceSQL →
substituted date. `ForbiddenTokenScanner` scans only `${VAR}` (`PLACEHOLDER_TOKENS` `:52–67`, regex
`"\\$\\{[A-Z][A-Z0-9_]*}"` `:161`) and `INTERNAL_TOKENS` (`:71–84`) — it has **no** `[[ ]]` rule (verified) —
so it stays **green**. A test must assert no `[[`/`]]` remains in any `GeneratedArtifact` body.

---

## V153 SEED CONFIRMATION

> Read `docs/build-specs/SPEC-blueprint-catalog.md` lines **1061–1117** (the `SqlModel` + `SourceSQL` drafted
> shapes). **This plan does NOT author the migration** — the single `V153` is owned by the Catalog lane
> (`IMPL-catalog-seed.md` Phase C). This section **confirms** the op-list content matches spec #6.

**`SqlModel` (TRANSFORM) — MATCH.**
- Single `sql-model` op (op 27) — `[read]` catalog `:1065`, spec #6 §B.5. ✔
- Param `steps` (`object[]`, user-tier, each step's `sql` accepts `[[ … ]]`) — `:1073`. ✔
- Optional `declared_output_schema` (user-tier `schema-spec-builder`, V153-seeded, §A.6 fallback) — `:1075–1077`. ✔
- Ports: in `input` (REQUIRED, reserved relation name) / out `sql_output` — `:1080–1083`. ✔
- `lake_layer` default = **silver** — `:1078–1079`. ✔ (matches the task's stated default.)

**`SourceSQL` (INGESTION) — MATCH.**
- Op-list `read-source(source_query, connector_instance_id) → add-audit-columns → write-sink(bronze)` —
  `:1092`, spec #6 §C.1. ✔
- Params `source_query` (user-tier, accepts `[[ … ]]`) **+ `connector_instance_id`** (system-derived) —
  `:1100–1107`. **Confirmed `connector_instance_id`, NOT `connector_id`** — `:1103`, `:1107`
  ("normalized to the `connector_instance_id` SINK convention (6-G06; was `connector_id`)"). ✔
- Optional `declared_output_schema` (user-tier `schema-spec-builder`, source-unreachable fallback) — `:1108–1109`. ✔
- Port: out `source_output` (role bronze, source-derived via JDBC prepare/metadata, NOT Calcite) — `:1110–1113`. ✔
- No input port (reads from connector, like all ingestion) — `:1110`. ✔

**Mismatches / notes:** **NONE.** The catalog draft and spec #6 agree on op-lists, param names (`steps`;
`source_query`+`connector_instance_id`), ports (`input`/`sql_output`; none/`source_output`), the optional
`declared_output_schema` fallback param on both, and `SqlModel.lake_layer`=silver. `connector_instance_id` is
the seeded connector field (NOT `connector_id`), consistent with the part-(b) branch above and the existing
`CodeGenerationService` connector-resolution call sites (`params.get("connector_instance_id")`, lines 1401/1420).

---

## APPLICATION ORDER (orchestrator, post-Builder-merge)

1. **(a)** Wire the rule-27 `sql-model` body into the Builder lane's op-walker (or the interim `case "SqlModel"`
   on `:814` if the walker is not yet landed). Inject `CalciteSqlModelValidator`.
2. **(b)** Insert the `source_query` short-circuit at the top of `ingestionSchema()` (`:861`); add the
   `jdbcTypeToPulse` map; inject the `connectorJdbcResolver` collaborator (TODO-anchor); add the explicit
   `SourceSQL` route only if running interim against the live switch.
3. **(c)** Add `MNEMONIC_TOKEN` + `mnemonicSlug` + `validateMnemonics` to `CodeGenerationService`; rewrite dbt
   model SQL; append `--vars` to the `dbt build --select tag:` invocation (`:597`); add the runtime resolve
   step (mirroring `:2174–2177`); string-substitute for PySpark/SourceSQL.
4. Run the ADR-0011 never-LLM assertions (both `sql-model` and `SourceSQL`) and the `ForbiddenTokenScanner`
   green check. Do not regress the byte-exact anchor (it uses no `sql-model`).
