# PULSE Chat — Tool contract strings, context-injection wrappers, plan-mode output

> **DRAFT — operator voice review pending.**
>
> This fragment authors the **op-emitting TOOL contract strings**, the **context-injection wrappers**, and the
> **plan-mode output** for PULSE Chat's composition surface, **grounded in the ACTUAL n8n source** (not in any prior
> n8n summary — the earlier `N8N-PROMPTS-REFERENCE.md` summary has since been removed). Every n8n claim below is `[read]` from a real file pulled from
> `github.com/n8n-io/n8n` master (raw URLs cited inline, fetched 2026-06-16). Inventions with no read source are flagged
> `> GUESS:`.
>
> This pays off the §7.19 "DRAFT — operator voice review pending" thread in `docs/ui/SPEC-ui-composition.md`: §7.19 B/C/D
> already drafted these contracts citing the *summary* doc; THIS fragment re-grounds them against the *actual source* and
> records the divergences the summary doc carries. Where this fragment and §7.19 agree, §7.19 stands; where the real
> source diverges from the summary, the divergence is logged in the table at the end and the corrected text is used here.
>
> **D1-FEEDBACK alignment (2026-06-16).** This revision aligns the fragment to the LOCKED 7-stage model
> (`D1-FEEDBACK-CHANGELIST.md` §F: **Router → Discovery → Build/Composer → Configure → Provision → Planner →
> Responder**). The tool surface is now bucketed by the stage that owns it: the §1 op-emitting tools serve
> **Build/Composer + Configure**; the §1.0 discovery tools serve **Discovery**; the new **§1.2 Provision-tier tools**
> (SOR / Domain / Connector / Dataset, incl. sample-data / schema-inference) serve the **Provision** stage and are
> surfaced from the EXISTING live chat backend (`ChatTools.java` / `ChatToolExecutor.java`), per §C/§D of the changelist
> ("RETAIN the existing `PulseSystemPrompt` coverage"). The new §0.1 records the changelist decisions this revision
> implements: **Mode/persona injection** (§E), **STRUCTURED set_params** (§E / 02-feedback), **per-blueprint config
> guidance** (§E), and the **Provision tools** (§F stage 5). The existing §0 source-grounding ledger, the §1 op-emitting
> strings + reasoning contract, the §2 context wrappers, the §3 plan-mode output, and the §4 divergence table are
> RETAINED where still valid; storage is corrected from a per-pipeline `default_storage_backend` to the **GLOBAL Mode
> variable** (§C storage correction).

---

## 0. Source-grounding ledger — the real n8n files read

`[read]` — pulled verbatim from `github.com/n8n-io/n8n` master on 2026-06-16:

| n8n artifact | Real tool name (`toolName`) | Raw URL read |
|---|---|---|
| `tools/add-node.tool.ts` | `add_nodes` | `https://raw.githubusercontent.com/n8n-io/n8n/master/packages/@n8n/ai-workflow-builder.ee/src/tools/add-node.tool.ts` |
| `tools/connect-nodes.tool.ts` | `connect_nodes` | `…/src/tools/connect-nodes.tool.ts` |
| `tools/update-node-parameters.tool.ts` | `update_node_parameters` | `…/src/tools/update-node-parameters.tool.ts` |
| `tools/node-search.tool.ts` | `search_nodes` | `…/src/tools/node-search.tool.ts` |
| `tools/node-details.tool.ts` | `get_node_details` | `…/src/tools/node-details.tool.ts` |
| `tools/get-documentation.tool.ts` | `get_documentation` | `…/src/tools/get-documentation.tool.ts` |
| `utils/context-builders.ts` | (context wrappers) | `…/src/utils/context-builders.ts` |
| `prompts/agents/planner.prompt.ts` | (plan-mode output) | `…/src/prompts/agents/planner.prompt.ts` |
| `tools/builder-tools.ts` / `subgraphs/builder.subgraph.ts` | (tool registry / wiring) | read via Exa code-search (file paths + tool exports confirmed) |

PULSE grounding `[read]`:
- `backend/.../com/pulse/chat/service/ChatTools.java` — the live tool registry (the `plan_*` mutation tier, the
  legacy `propose_*` ALIASES of those — drop the alias vocabulary going forward, keep the `plan_*` contract — and the
  direct-write tools).
- `backend/.../com/pulse/chat/service/ChatToolExecutor.java` — the executor switch + per-tool result envelopes.
- `backend/.../com/pulse/chat/service/PulseSystemPrompt.java` — ABSOLUTE_RULES #16 (PLAN/APPLY-only mutation), #17 (dev-only, never promote), the credential-dialog "no secret values in chat" rule (`:309`,`:336`,`:343`).
- `docs/ui/SPEC-ui-composition.md` §7.3 B (mutation tier → one typed op each), §7.4 (`composition.*` Command-Log mapping), §7.9 (canonical+staging BLEND = Plan Preview), §7.17 A (tool surface), §7.19 B/C/D (the prior draft this re-grounds).

PULSE mutation tier (the contract these tool strings must serve): each mutation tool emits **one `composition.*` Command-Log command** (`composition.addInstance` / `wirePorts` / `updateInstance` / `removeInstance` / `removeWiring` / `renameInstance` — `[read]` §7.4 table), into a per-turn **op-queue** that drains into a **staging graph**; the **canonical + staging BLEND is the Plan Preview**; only `apply_plan` writes the canonical graph + Command Log (`[read]` §7.9, ARCH-009, `PulseSystemPrompt` #16).

---

## 0.1 D1-FEEDBACK decisions this revision implements

The `D1-FEEDBACK-CHANGELIST.md` (§E final decisions + §F locked stage model) drove four concrete changes to the tool +
context surface. Each is implemented in the cited section below and grounded in live PULSE source.

| Changelist decision | Where in this fragment | Grounding |
|---|---|---|
| **Mode/persona injection** (§E, §C storage correction) — inject the active Mode (GCP vs DPC) + storage mapping as a per-deployment CONSTANT, not a per-turn tool. | **§2.1** new `<runtime_mode>` context tag. | `[read]` `RuntimeAuthorityService.getActivePersona()` surfaced at `TenantRuntimeReadinessService.java:185` (`category.put("activePersona", runtimeAuthorityService.getActivePersona().name())`); persona enum `GCP_PULSE` / `DPC_PULSE` `[read RuntimePersona.java:7-8]`; storage materialization presets `[read RuntimeAuthorityService.java:134-160]`. |
| **STRUCTURED set_params** (§E, §B D8, 02-feedback) — no natural-language sub-LLM; typed-param object + Calcite/contract validation is the safety net. | **§1** `set_params` string + **§1.1** divergence (RETAINED, re-confirmed). | `[read ChatTools.java:185-191 plan_set_step_params / :274-281 configure_step_params]` (both take a structured `params` object, not a `changes: string[]`); contrast n8n `[read update-node-parameters.tool.ts]`. |
| **Per-blueprint config guidance** (§E) — Configure/Build stages inject param-by-param guidance (purpose/defaults/gotchas/tier) — the PULSE analogue of n8n's `get_documentation` / `node_recommendations`. | **§2.2** new `<blueprint_config_guidance>` context injection. | Sourced from #5 catalog metadata (`get_blueprint_detail` `[read ChatTools.java:115-119]` returns "parameters, ports, usage guidance, valid layers, emit strategy, schema behavior") + the 03-best-practices technique/category guides. |
| **Provision-stage tools** (§F stage 5, §C, §D) — surface the EXISTING SOR / Domain / Connector / Dataset operations (incl. sample-data / schema-inference) as the Provision tier; RETAIN `PulseSystemPrompt` coverage. | **§1.2** new Provision-tier tool strings. | `[read]` live registry `ChatTools.java` + executor `ChatToolExecutor.java` (case branches `:247-300`) + the SOR/dataset/schema coverage in `PulseSystemPrompt.java` (Family A/B connector vocabulary `:255-339`, schema-inference table `:538-548`, processing_datetime persistence `:584-587`). |

> `[read]` The persona line the changelist names is confirmed verbatim: `TenantRuntimeReadinessService.java:185` calls
> `runtimeAuthorityService.getActivePersona().name()`. The storage mapping the changelist asserts (GCP: bronze+silver =
> BQ-managed Iceberg, gold = BQ-native; DPC: all Hive+Parquet/S3) is confirmed in the persona presets:
> `buildGcpPreset` maps `bronze`/`silver` → `iceberg_bq_managed`, `gold` → `bq_native`
> (`RuntimeAuthorityService.java:136-138`); `buildDpcPreset` maps `bronze`/`silver`/`gold` → `parquet` over
> `S3A_OBJECT_STORAGE` (`RuntimeAuthorityService.java:158-169`).

---

## 1. The op-emitting TOOL contract strings

Each PULSE composition mutation tool carries, IN ITS DESCRIPTION, a **REQUIRED `reasoning` field** — the PULSE analogue
of n8n's `initialParametersReasoning` (`[read add-node.tool.ts]`). The n8n field is, verbatim:

> `[read add-node.tool.ts]` — the `initialParametersReasoning` Zod `.describe()`:
> `"REQUIRED: Explain your reasoning about initial parameters. Consider: Does this node have dynamic inputs/outputs? Does it need mode/operation/resource parameters? For example: \"Vector Store has dynamic inputs based on mode, so I need to set mode:insert for document input\" or \"Gmail needs resource:message and operation:send to send emails\""`
>
> and the tool body's instruction block: `"Provide both: 1. initialParametersReasoning - Explain why you're choosing specific initial parameters or using {} 2. initialParameters - The actual parameters…"` plus `"Consider the initialParametersReasoning first, then set initialParameters accordingly."`

PULSE adopts the **reasoning-first** discipline (reason BEFORE you set params; the reasoning is surfaced in the Plan
Preview as the per-step "why"), in data-engineering voice. Tool names follow §7.3 B / §7.17 A N1–N7.

```text
add_blueprint_instance
  Add ONE Blueprint instance to the staging composition. Emits a composition.addInstance op into the
  turn's op-queue — it does NOT write the canonical pipeline (only apply_plan does). Auto-names and
  auto-orders within the version.
  REQUIRED reasoning: reason BEFORE you stage it (the n8n reasoning-first discipline). Explain WHY this
  Blueprint fits the intent: which dataset(s) it consumes and which it produces; the medallion layer
  (bronze raw / silver curated / gold serving); the entity change pattern (SCD2 for slowly-changing
  master data, append for transactional facts, overwrite for full reloads); and the key param choices you
  will set next (e.g. SCD2 business_key, partition column, time-grain sensing). Your reasoning is shown to
  the user as the "why" for this step in the Plan Preview, so write it for a data engineer, not yourself.
  Do NOT set canonical storage / lake-layer / table-format fields here — storage is fixed by the deployment's
  global Mode (GCP vs DPC), surfaced read-only in <runtime_mode>, NOT a per-step or per-pipeline choice — and
  do NOT set DQ-expectation fields here (add a DQValidator step instead). [ARCH-018]

wire_ports
  Connect ONE output port to ONE input port in the staging composition. Emits a composition.wirePorts op
  (additive). Use the Blueprint's EXACT declared port names (e.g. raw_output -> data_input,
  cleaned_output -> data_input, validated_output -> data_input) — NEVER generic "input"/"output". Both
  instances must already exist in the version, and the port types AND the dataset-schema CONTRACTS must be
  compatible (a wire that breaks the schema contract is rejected, not auto-fixed).
  REQUIRED reasoning: name the dataset columns that flow across this wire, and state why this is the
  correct upstream->downstream order in the medallion flow (bronze before silver before gold; DQ gates
  before serving). If you are unsure of the upstream columns, call get_step_schema FIRST — never guess
  what flows across a wire.

set_params
  Set or update an instance's params in the staging composition. Emits a composition.updateInstance op.
  Takes a STRUCTURED params object (typed keys -> values) DIRECTLY — there is NO natural-language sub-LLM
  step (unlike n8n's update_node_parameters, which routes a `changes: string[]` through a param-updater
  chain). PULSE params are typed against the Blueprint param schema and the dataset contract, and
  Calcite/contract validation is the safety net — so you propose the structured object and validation
  disposes; you never hand a free-text "change the URL to ..." string. (§1.1, §4 row 3.)
  REQUIRED reasoning: "why these params" — tie EACH value to the dataset shape, the grain, the change
  pattern, or the user's stated SLA. (Good: "business_key = employee_id because that is the natural key
  the SCD2 history hangs on." Bad: "configured the merge keys.") The reasoning IS the Plan Preview "why".
  Forbidden keys are rejected at the boundary: canonical storage / lake / table-format fields
  (Mode-fixed, see <runtime_mode> — not per-step), pipeline orchestration-policy fields (use
  set_pipeline_setting), and DQ-expectation fields (use the DQValidator path). An empty param payload is
  rejected. [ARCH-018]

remove_instance
  Delete a step AND all its wirings from the staging composition. Emits a composition.removeInstance op.
  Destructive and structural — use ONLY when the step genuinely no longer belongs. NEVER use
  remove_instance + add_blueprint_instance as a way to "reset" or "reconfigure" a step: to change config,
  call set_params; that destroy-and-recreate dance silently drops the step's wirings and any attached DQ
  rules. If the step has downstream consumers, state the impact radius in your message before staging it.

remove_wire
  Delete ONE specific edge from the staging composition. Emits a composition.removeWiring op. The wire
  must exist. Warn if removing it strands a downstream input port unfed.

rename_instance
  Rename a step and fix EVERY reference to it (wirings, expressions, downstream column lineage). Emits a
  composition.renameInstance op. The new name must be unique within the version. (n8n's rename auto-updates
  all connection references and expressions; PULSE does the same across wirings + derived-column
  expressions.) [read renameNode operation, n8n operations-processor.ts]

set_pipeline_setting
  Set a PORTLESS Pipeline Setting (schedule-and-triggers, rollback-on-failure). Emits a
  setPipelineSetting op that maps to the pipeline.update command-type (a pipeline-level setting,
  NOT a composition.* type — 3-09 folds setName/setPipelineSetting into pipeline.update; there are
  exactly SIX composition.* types).
  REQUIRED reasoning: for a schedule, tie the cron to the upstream landing window or the user's SLA, and
  state max_active_runs from ordering sensitivity (SCD2 / incremental merges => no overlapping runs).
  Treat catchup = true as a SEPARATE explicit decision and state its consequence (on first deploy Airflow
  schedules one run for EVERY interval from the start date to today). [Planner Packet 3a]
```

**Discovery-tier description strings** (read-only; NO reasoning field; awareness/detail, NOT catalog search). PULSE
deliberately diverges from n8n here: n8n's `search_nodes` searches a ~500-node catalog by name OR by output
connection-type `[read node-search.tool.ts]`; PULSE has ~50 Blueprints DUMPED into the cached system prompt (LOCKED §6),
so there is NO blueprint-search tool — discovery is in-context awareness + per-detail lookup.

```text
get_blueprint_detail   — Full contract for one Blueprint: params, in/out ports, declared op-list, valid
                         medallion layers, emit strategy, schema behavior. (n8n analogue: get_node_details,
                         "Get detailed information about a specific n8n node type including properties,
                         available connections, and up to 5 example configurations." [read node-details.tool.ts].)
                         You already have every Blueprint's summary in context — call this only when the
                         summary line is not enough.
get_blueprint_op_list  — The Blueprint's declared op-list (ADR 0012): each op with its schema effect, row
                         effect, and any side-output (e.g. DQ quarantine). Use it to reason about what a
                         step does to schema and rows WITHOUT guessing. [ADR 0012]
get_composition        — The current CANONICAL composition (instances + wirings) for a pipeline.
get_composition_overview — Compact summary: step count, wire count, layers present, dangling ports,
                         unresolved-schema counts. Use to orient before a build or an explain.
get_step_schema        — Inferred output columns of a step (name/type/nullable/pii), following wirings
                         backward. Call before wiring INTO a step or setting params that depend on upstream
                         columns. The source is rule | declared | discovery — NEVER an LLM guess. [ADR 0011]
validate_structure     — Graph-level checks over the staging version: no orphan steps, no cycles, every
                         step reachable, every required input port satisfied. (n8n ships a
                         validate_structure tool [read builder-tools.ts registry]; PULSE's is stronger
                         because ports are typed.)
validate_configuration — Per-step param/port completeness vs the Blueprint contract and the
                         required-for-runtime punch-list. The failure this catches is a step that "looks
                         saved" but is missing a runtime field. (n8n ships validate_configuration too.)
validate_plan          — Run the deterministic Builder pre-flight over the STAGING graph. If the plan would
                         not compile, it cannot be applied — surfaces the blocker per step. [ADR 0012/0013]
validate_sql_expression — Calcite-validate a derived-column expression or a sql-model body against its input
                         schema, returning the output columns or a clean error. This is how the LLM AUTHORS
                         an expression/SQL safely — it proposes, Calcite disposes; the LLM never writes
                         codegen directly. [ADR 0013/0024]
```

> RESOLVED (operator 2026-06-16): the PULSE tool NAMES above are the §7.3 B / §7.17 A op-emitting / discovery contract
> names — **snake_case, zero hyphens** (the live `ChatTools.java` convention). Some re-time live tools onto the op-queue
> (`plan_add_step`->`add_blueprint_instance`, `plan_wire_ports`->`wire_ports`, `plan_set_step_params`->`set_params`,
> `update_pipeline_orchestration`->`set_pipeline_setting`); `get_blueprint_op_list` / `validate_plan` /
> `validate_sql_expression` / `get_step_schema` / `get_composition_overview` are NEW. The REQUIRED-reasoning DEMAND is
> `[read]` from `add-node.tool.ts`; the forbidden-key / remove-is-destructive / catchup-explicit / DQ-separation specifics
> are `[read]` from `PulseSystemPrompt` ABSOLUTE_RULES + Planner Packet. The data-engineering WORDING is what the
> operator-voice pass tunes.

### 1.2 The Provision-tier TOOL contract strings (SOR / Domain / Connector / Dataset)

> The LOCKED stage model (§F) adds a **Provision** stage — the n8n `configurator.prompt.ts` analogue — that builds and
> configures the "helper/configuration objects" the pipeline depends on: **Producer / System-of-Record, Domains,
> Connectors, Datasets** (incl. **sample-data / schema-inference**). These are NOT `composition.*` ops (they are entity
> CRUD against the SoR registry — a distinct command family from composition), **BUT under the universal Plan→Apply gate
> (3-18 / §7.16 #18) they ARE plan-producing**: entity creation stages into the Plan Preview and persists only on Apply,
> never a silent direct-write. The live `[read]` "nothing saved until you call this tool" behavior is **superseded** by
> the gate — nothing persists until **Apply**. Per §C/§D, the Provision tier **surfaces the EXISTING live chat
> tools verbatim from the backend** — it does NOT invent a parallel surface. Every string below is `[read]` from the
> live registry `ChatTools.java` and confirmed wired in the executor switch `ChatToolExecutor.java:247-300`; the
> elicitation discipline each carries is `[read]` from `PulseSystemPrompt.java` (cited inline). The Provision stage's
> system prompt RETAINS that `PulseSystemPrompt` coverage (Family A/B connector vocabulary, schema-inference table,
> processing_datetime persistence) rather than re-deriving it.

**SoR / Domain / Connector / Dataset CRUD** (live `[read PulseSystemPrompt.java:58,69-72]` says the create_* tools are
the only persistence — "nothing is saved until you call this tool"; under the universal gate (3-18) this becomes
**plan-producing**: the create_* op stages into the Plan Preview and persists only on **Apply**):

```text
list_data_sources   — List all registered Systems of Record (SORs) with their connectors and dataset counts.
                      Use to see what data sources exist before suggesting a pipeline.
                      [read ChatTools.java:19-21 / executor :248]
create_data_source  — REQUIRED to persist a new SOR. Nothing is saved until you call this. Register a new
                      System of Record in the tenant. Args: name (req), domain_name / domain_id, description.
                      Returns the created SOR + internal id. [read ChatTools.java:36-43 / executor :249]
list_domains        — List all data domains for the tenant: names, descriptions, business-date config.
                      [read ChatTools.java:58-60 / executor :250]
create_domain       — REQUIRED to persist a new domain. Args: name (req), description, current_business_date
                      (YYYY-MM-DD), business_date_grain (DAILY | DAILY_BUSINESS_DAY | WEEKLY | MONTHLY). The
                      domain owns the business calendar the schedule/sensor mnemonics resolve against.
                      [read ChatTools.java:62-70 / executor :251]
list_connectors     — List connectors for one SOR: names, types, credential statuses, dataset counts.
                      [read ChatTools.java:23-27 / executor :253]
get_connector_type_schema — REQUIRED before create_connector for any NEW connector. Returns the connection_spec
                      (JSON Schema) for a connector type — the canonical field names + required fields + which
                      fields are pulse_role:env_metadata vs pulse_role:credential — so you pass real connector
                      vocabulary (S3: bucket+path_prefix; SFTP: folder_path+file_pattern; Oracle: host+port+sid),
                      not made-up names. [read ChatTools.java:330-334 / executor :284; PulseSystemPrompt.java:230,303-309]
create_connector    — REQUIRED to persist new connector metadata. Create a connector instance on an existing
                      SOR. Args: sor_name + connector_name (req), connector_type, config (env_metadata only),
                      credential_refs (SecretRefs, preferred), credentials (legacy — refs only). NEVER claim the
                      connection is ready until credential status is confirmed. Family-branch first (Absolute
                      Rule #14): Family A object-storage connectors take NO user config/credentials (auth is
                      storage_backend-managed); Family B external-SOR connectors take env_metadata config + a
                      deferred credential dialog. [read ChatTools.java:45-56 / executor :252; PulseSystemPrompt.java:210-339]
get_storage_paths   — REQUIRED for object-storage (Family A / S3-compatible) connector creation. Resolves
                      bucket + SOR-level path prefix from the tenant's storage_backends row + naming convention;
                      surface the resolved values transparently, NEVER ask the user for bucket / path / region /
                      endpoint. direction=source -> SRC folder; sink -> outgoing_extracts. [read ChatTools.java:344-351
                      / executor :286; PulseSystemPrompt.java:276-290]
request_credential_attach — Open the credential dialog mid-conversation so the user attaches secret VALUES to a
                      Family B connector (JDBC/Kafka/SFTP/REST/...). This is a UI intent: it carries NO secrets in
                      chat and mutates no product state — the secret value goes through the dialog into Secret
                      Manager as a SecretRef. PULSE is the dev-developer's tool to push keys in dev; drive this
                      flow, do NOT punt to "have your platform team attach credentials." Object-storage (Family A)
                      connectors do NOT take user-entered credentials. [read ChatTools.java:336-342 / executor :285;
                      PulseSystemPrompt.java:159-161,339]
list_datasets       — List datasets for a connector instance, a SOR, or the whole tenant: schema format,
                      classification, definition type. [read ChatTools.java:29-34 / executor :255]
create_dataset      — REQUIRED to persist a new dataset on an existing SOR. Args: sor_name + name (req),
                      description, classification, schema_snapshot ({fields:[{name,type,pii}]} — ALWAYS include
                      when you know the columns; omitting known schema is a bug), time_grain, current_asof,
                      file_naming_pattern, processing_datetime_source (filename_segment | file_arrival_time |
                      airflow_run_time — PERSIST the user's Phase-2g answer here so codegen reads it, not guesses),
                      connector_instance_id, partition_strategy, write_mode, table_format_hint. PII is per COLUMN.
                      [read ChatTools.java:72-94 / executor :254; PulseSystemPrompt.java:199-207,536,584-587]
```

**Sample-data / schema-inference** (the Provision stage owns it; ADR 0011 — schema inference is deterministic, rule /
discovery, NEVER an LLM guess; the in-chat methods are `[read PulseSystemPrompt.java:538-548]`):

```text
derive_dataset_schema       — Discover a dataset's schema from a JDBC table, a SQL query, or an uploaded sample.
                      Returns column names, types, nullability, and PII/confidential classification.
                      source_type = table | query | sample (table/query need valid credentials; sample does not).
                      Present the available inference methods to the user as a table and let THEM choose — do NOT
                      assume an upload. After discovery, register via create_dataset_from_discovery.
                      [read ChatTools.java:472-494 / executor :299; PulseSystemPrompt.java:538-548]
create_dataset_from_discovery — Register a dataset using a schema derived by derive_dataset_schema. Pass the
                      fields, classification, discovery_method, and discovery_proof from the discovery result, plus
                      time_grain / asof_column_name / current_asof. Persists the dataset with full discovery
                      provenance. [read ChatTools.java:496-525 / executor :300]
```

**Sink-target provisioning** (a SoR with registry_type=TARGET — where pipelines publish; pairs with the §1 sink
writers):

```text
list_sink_targets   — List registered publish destinations (BigQuery / Snowflake / S3 lake / Kafka, ...). Use
                      before proposing a sink step. [read ChatTools.java:312-314 / executor :281]
create_sink_target  — Create a sink target (a SoR with metadata.registry_type='TARGET'). Args: name (req),
                      description, domain_id (req — canonical). [read ChatTools.java:316-322 / executor :282]
```

> `[read]` Every Provision-tier string above is the live tool's own description, condensed; the tool names, arg sets,
> required-vs-optional, and enum values are read directly from `ChatTools.java` (line ranges cited per tool) and the
> executor wiring from `ChatToolExecutor.java:247-300`. The elicitation rules folded into the strings (Family A vs B
> connector branching, get_storage_paths-not-ask, credential-dialog-not-chat, schema-inference-as-a-table,
> processing_datetime persistence) are `[read]` from `PulseSystemPrompt.java` at the lines cited — this is the
> "RETAIN the existing coverage" directive (§D) made concrete.
> **> RESOLVED (operator 2026-06-16):** the **stage assignment** (these tools belong to a distinct **Provision** stage)
> is the changelist's §F decision. Today they all live in one flat `ChatTools` registry; the Build/Composer and Provision
> stages SHARE this backend registry, and the stage split is a prompt-architecture boundary (which system prompt
> advertises which tools). Per-stage tool allow-lists are **derived mechanically from tier** — discovery→read tools,
> composer→`add_blueprint_instance`/`wire_ports`/`remove_*`, configure→`set_params`/`validate_*`, **provision→the
> Provision-tier SOR/Domain/Connector/Dataset + sample-data/schema-inference + sink-target tools listed in §1.2** (3-F3).

### 1.1 What n8n does that PULSE deliberately drops (per-tool)

- **n8n `add_nodes` carries TWO fields** (`initialParametersReasoning` + `initialParameters`) and sets params AT ADD
  TIME `[read add-node.tool.ts]`. PULSE SPLITS this: `add_blueprint_instance` (with the reasoning) then `set_params` as
  a separate op — because PULSE's params are validated against the typed Blueprint param schema + dataset contract, and
  the staging-graph BLEND wants each op individually previewable. PULSE keeps n8n's reasoning-first discipline, drops
  the params-at-add-time coupling. **> RESOLVED (operator 2026-06-16):** `add_blueprint_instance` carries **NO initial
  `params`** — it is ALWAYS followed by a separate `set_params` op (§7.16, 3-F4), so each op is individually previewable
  and params validate against the typed Blueprint schema + contract separately.
- **n8n `update_node_parameters` runs a SEPARATE param-updater LLM sub-chain** (`createParameterUpdaterChain`) that takes
  NATURAL-LANGUAGE change strings (`changes: string[]`, e.g. "Set the URL to https://api.example.com") and a filtered
  node-properties JSON, and emits the concrete params `[read update-node-parameters.tool.ts]`. PULSE's `set_params`
  takes a STRUCTURED `params` object directly (no sub-LLM), because Blueprint params are typed and Calcite/contract
  validation is the safety net, not a second LLM. **Divergence logged** (§4 row 3).
- **n8n `connect_nodes` AUTO-CORRECTS backwards connections** (sub-node is always source; "The tool will AUTO-CORRECT if
  you specify them backwards"), and infers the connection type from node I/O `[read connect-nodes.tool.ts]`. PULSE's
  `wire_ports` does NOT auto-correct direction: ports are EXPLICITLY named and typed, so a backwards or
  contract-incompatible wire is REJECTED with a clean error, never silently swapped. **Divergence logged** (§4 row 2).

---

## 2. The context-injection wrappers

Per turn, the system concatenates labeled context parts into the user message — the n8n `=== SECTION ===` / context-tag
pattern. The n8n builder concatenates labeled blocks via `createContextMessage(contextParts)` which
`filter(Boolean).join('\n\n')` `[read context-builders.ts]`. PULSE adopts the same concatenation + the same per-tag
shapes, adapted to PULSE's composition + schema-visibility + conflict-overlay state. These tags are STRIPPED from the
streamed text before it reaches the user (the n8n stream-processor strips them; §7.14).

The n8n wrappers, verbatim `[read context-builders.ts]`, and the PULSE analogue for each:

| n8n wrapper (verbatim) | PULSE analogue |
|---|---|
| `<current_workflow_json>{trimmed JSON}</current_workflow_json>` + `"Large property values may be trimmed. Use get_node_parameter tool for full details."` (`buildWorkflowJsonBlock`) | `<current_composition>` |
| `<selected_nodes>The user has explicitly selected the following node(s) for you to focus on. When the user says "this node", "it", "this", or similar deictic references, they refer to these selected nodes. …</selected_nodes>` (`buildSelectedNodesContextBlock`) | `<selected_step>` (deictic anchor) |
| `<execution_status>{success\|error\|issues_detected\|no_execution}</execution_status>` + `<data_flow>Node1 (5 items) → Node2 (0 items)</data_flow>` + `<nodes_not_executed>` + `<nodes_with_empty_output>` (`buildSimplifiedExecutionContext`) | `<run_status>` |
| `<execution_data>{…}</execution_data>` + `<execution_schema>{…}</execution_schema>` (`buildExecutionContextBlock`) | folded into `<dataset_schemas>` + `<run_status>` |
| plain-text block: `Previous conversation summary:` / `Original request: "…"` / `Previous actions:` / `Current request: "…"` (`buildConversationContext`) — NOT an XML tag | plain-text conversation-summary block (NOT a tag) |
| — (no analogue in n8n) | `<dataset_schemas>` (PULSE: schema-as-contract) |
| — (no analogue in n8n) | `<schema_visibility>` (PULSE: ADR 0011 schema status) |
| — (no analogue in n8n) | `<conflict_overlay>` (PULSE: the §2 CONFLICT OVERLAY) |
| — (no analogue in n8n) | `<runtime_mode>` (PULSE: the Mode/persona + storage constant — §2.1) |
| n8n's per-node `get_documentation` content (a TOOL, not a tag) | `<blueprint_config_guidance>` (PULSE: INJECTED per-param guidance for Build/Configure — §2.2) |
| — (no analogue in n8n) | `<session_facts>` (PULSE: structured short-term memory — reuse/DQ/link decisions; f22 — §2.2) |

The exact PULSE wrapper text:

```text
<current_composition>
{the staging-or-canonical CompositionView JSON: instances[] (name, blueprintKey, medallion layer, params,
 ports) + wirings[] (source -> target by name + port)}
Large param values may be trimmed. Use get_blueprint_detail or get_step_schema for full detail. This is
the ground-truth graph for THIS turn: while you have ops staged it ALREADY reflects them (the staging
graph), so reading it stays consistent with what you have built so far this turn.
</current_composition>

<dataset_schemas>
{per dataset in play: name, grain, time_grain, and columns[] with type, nullable, and a per-column PII flag}
PII is per COLUMN — classify in a table, never call a whole dataset "PII".
</dataset_schemas>

<selected_step>
The user has explicitly selected the following step in the inspector for you to focus on:
{instance name + its Blueprint + medallion layer}.
When the user says "this step", "it", "this", "here", or similar deictic references, they mean THIS step.
Resolve deixis against it. Look up full step detail by matching the name in <current_composition>.
</selected_step>

<schema_visibility>
{per step: schemaStatus = resolved | stale | unresolved | overridden; and which ports carry a manual
 schema override}
A 'stale' or 'unresolved' status means you must NOT assume downstream columns — call get_step_schema or
ask. Do NOT wire into an unresolved port.
</schema_visibility>

<conflict_overlay>
{open schema conflicts from the propagation graph: conflictId, the two sides (declared vs inferred /
 upstream-changed), and the step/port each touches}
These are unresolved schema conflicts on the canvas (the CONFLICT OVERLAY, §2). Surface the relevant one
to the user before building on top of a conflicted port; do NOT silently pick a side.
</conflict_overlay>

<run_status>
{success | error | no_run, plus per-step row counts from the last run, e.g.
 "Ingest (1,240 rows) -> Clean (1,240) -> DQValidator (12 quarantined)"}
</run_status>
```

The conversation-summary is a **plain-text header block, NOT an XML tag** — exactly as n8n's `buildConversationContext`
emits it `[read context-builders.ts]` (n8n uses the labels `Previous conversation summary:` / `Original request:` /
`Previous actions:` / `Current request:`; PULSE shortens to):

```text
Previous summary: {rolling summary of the conversation so far}
Original request: {the user's first ask this session}
Prior actions: {ops staged/applied and plans approved so far}
Current request: {the user's latest message}
```

> `[read]` — `<current_composition>` / `<selected_step>` / `<run_status>` + the plain-text summary block are the direct
> PULSE analogues of n8n's `<current_workflow_json>` / `<selected_nodes>` / `<execution_status>` + the
> `buildConversationContext` plain-text block `[read context-builders.ts]`. The "large values may be trimmed; use
> get_…" note is `[read]` verbatim from `buildWorkflowJsonBlock` (PULSE swaps `get_node_parameter` -> `get_step_schema` /
> `get_blueprint_detail`). The deictic sentence in `<selected_step>` is a near-verbatim adaptation of n8n's
> `<selected_nodes>` sentence.
> RESOLVED (operator 2026-06-16, DEFAULT): `<dataset_schemas>`, `<schema_visibility>`, and `<conflict_overlay>` are
> PULSE-SPECIFIC tags with NO n8n analogue (n8n's node graph is untyped). They exist because PULSE has ADR 0011
> schema-as-contract + the §2 CONFLICT OVERLAY, so the proposed field sets above ARE the contract; verify against the §2
> overlay renderer when it is built.

### 2.1 `<runtime_mode>` — the Mode/persona injection (per-deployment CONSTANT)

Per changelist §E + §C (storage correction), the prompt-builder reads
`RuntimeAuthorityService.getActivePersona()` once per deployment and injects the active **Mode** (GCP vs DPC) plus the
**storage mapping** into the system-prompt context. This is a **per-deployment CONSTANT** (ADR 0001), **NOT a per-turn
tool call** — it is part of the cached system prompt, alongside the dumped Blueprint catalog (LOCKED §6). It exists so
the LLM never hand-types a storage / lake / table-format field and never asks the user "DPC or GCP?" per pipeline (the
old per-pipeline `default_storage_backend` framing is REVERSED — storage is global Mode, not pipeline-level).

```text
<runtime_mode>
Active deployment Mode: {GCP | DPC}  (RuntimeAuthorityService.getActivePersona() — GCP_PULSE | DPC_PULSE).
This is a per-deployment CONSTANT — it is the SAME for every pipeline in this tenant deployment and the user
does NOT choose it per pipeline. Storage backend, lake layer, and table format are DERIVED from this Mode:
  GCP : bronze + silver = BigQuery-managed Iceberg (iceberg_bq_managed) ; gold = BigQuery native (bq_native).
  DPC : bronze + silver + gold = Hive + Parquet (parquet) on S3.
Format nuance (DB-enforced; c2 — PulseSystemPrompt.java:386-395): gold-on-GCP is bq_native ONLY (LOCKED) —
never override it; iceberg_bq_managed is GCP-only (BQ owns the layout below the prefix); parquet is DPC-only
(no ACID metadata). NEVER set or ask for storage_backend / lake_format / table_format — they follow the
Mode. Surface the storage target read-only if the user asks "where does this land"; otherwise stay silent.
</runtime_mode>
```

> `[read]` The injected value is `RuntimeAuthorityService.getActivePersona().name()` — exactly the call at
> `TenantRuntimeReadinessService.java:185` — and the persona enum is `GCP_PULSE` / `DPC_PULSE`
> (`RuntimePersona.java:7-8`). The storage mapping is `[read]` from the persona presets: GCP bronze/silver →
> `iceberg_bq_managed`, gold → `bq_native` (`RuntimeAuthorityService.java:136-138`); DPC bronze/silver/gold →
> `parquet` over `S3A_OBJECT_STORAGE` (`RuntimeAuthorityService.java:158-169`). The "per-deployment constant, injected
> not a per-turn tool" decision is `[read]` changelist §E (ADR 0001).
> RESOLVED (operator 2026-06-16, DEFAULT — wording is the operator-voice pass): the §E decision (Mode is an INJECTED
> per-deployment constant, not a per-turn tool) is `[read]`; the exact TAG text + the "stay silent unless asked" behavior
> above is the proposed wording the voice pass tunes. There is also a read-only `preview_runtime_authority` TOOL
> (`[read ChatTools.java:418-422]`) returning the same persona/storage authority on demand — the tag is primary, the tool
> a fallback for deep introspection.

### 2.2 `<blueprint_config_guidance>` — per-blueprint config guidance (Build + Configure stages)

Per changelist §E, the **Configure** and **Build** stages inject **per-blueprint configuration guidance** — the
blueprint's param-by-param guidance (what each param does, sensible defaults, gotchas, user-tier vs system-derived) —
so the LLM configures against real guidance, not guesses. This is the PULSE analogue of n8n's `get_documentation` /
`node_recommendations`. The content is sourced from **#5's catalog metadata** (`get_blueprint_detail` returns
"parameters, ports, usage guidance, valid layers, emit strategy, schema behavior" — `[read ChatTools.java:115-119]`)
joined with the **03-best-practices technique + category guides**. It is injected for the Blueprint(s) currently in
play (the selected step's blueprint, or the blueprints just added this turn), NOT the whole catalog (the catalog
summary is already dump-cached in §6).

```text
<blueprint_config_guidance>
Per-param guidance for the Blueprint(s) in play this turn ({blueprintKey list}):
  param: {name}
    purpose : what this param controls
    tier    : user-supplied | system-derived  (NEVER hand-type a system-derived param — storage/lake/format/
              connector refs/calendar refs are resolved by PULSE; see <runtime_mode>)
    default : the sensible default (or "ask" when there is none)
    gotcha  : the failure mode if set wrong (e.g. "wrong has_header silently shifts every column")
Source: #5 catalog metadata (get_blueprint_detail) + the matching 03-best-practices category guide +
any applicable 03 technique guide (SCD2 / incremental-merge / partitioning / DQ-gating / ...).
TARGETED, NOT CORPUS-WIDE (f22 — PulseSystemPrompt.java:927-928): inject the dbt best-practice cards +
example packets for the ACTIVE Blueprint set ONLY — never the generic corpus-wide examples when a narrower
packet exists. Keep prompt-talk aligned with the registry generate-vs-reuse decision.
</blueprint_config_guidance>

<session_facts>
{structured short-term memory accumulated this session: reuse decisions already made, DQ rules already
 suggested, entity↔dataset links already established, the registry generate/reuse/reference outcome per
 dbt asset}
Treat these as short-term memory (f22 — PulseSystemPrompt.java:927-928): consult them before re-deciding a
reuse outcome, re-suggesting a DQ rule, or re-asking something already settled this session — don't make
the engineer repeat themselves.
</session_facts>
```

> `[read]` `get_blueprint_detail` is a live tool returning per-param + usage guidance (`ChatTools.java:115-119`,
> executor `:260`); the user-supplied-vs-system-derived distinction is `[read PulseSystemPrompt.java:230` (env_metadata
> fields) `, :240-243` (inferred fields)`]` and `03-best-practices §0.4` (system-derived params NEVER hand-typed,
> ADR 0023). The Configure/Build-stage injection decision is `[read]` changelist §E.
> RESOLVED (operator 2026-06-16, DEFAULT): §E says "INJECT", so the tag is primary (a STATIC injection pre-baked from the
> catalog, with `get_blueprint_detail` as the on-demand fallback for a selected step). The field set
> (purpose/tier/default/gotcha) is the proposed shape, matching the 03-guide "Configuration / Best practice" structure;
> the join (catalog metadata + 03 guides) and the budget are verified at build time.

---

## 3. The plan-mode output structure (= PULSE Plan Preview -> Apply Plan)

PULSE's planner emits a structured plan that IS the Plan Preview -> Apply Plan gate (§7.2, §7.9; ARCH-009). This is
adapted from the ACTUAL n8n `planner.prompt.ts`, whose `OUTPUT_FORMAT` is, verbatim `[read planner.prompt.ts]`:

> - `summary`: 1–2 sentences describing the workflow outcome in plain language
> - `trigger`: what starts the workflow, described simply (e.g. "Runs every morning at 7 AM")
> - `steps`: short list of what happens, each step is one sentence. Include `suggestedNodes` for the builder but keep the description non-technical.
> - `additionalSpecs`: only non-obvious things the user must know. **NEVER mention API keys, credentials, connecting accounts, or authentication** — these are always required and stating them wastes the user's time. Only include genuinely surprising requirements.

and whose `RULES` say, verbatim `[read planner.prompt.ts]`: `"Do not generate workflow JSON."` · `"Do not mention
internal n8n node type names in steps — describe what happens in plain language."` · the n8n planner's ROLE/GOAL:
`"Your audience is often non-technical… Focus on WHAT happens and WHY, not HOW it's implemented… do not include
[credentials, configuration, node parameters, routing logic]."`

PULSE adopts this shape, ADDS `blueprintKey` + `medallionLayer` (internal, for the canvas ghost) and a per-step
`reasoning` (surfaced "why"), and REMOVES the credentials concern entirely (PULSE secrets are SecretRefs resolved at
build/package time, never in a plan — `[read PulseSystemPrompt:309,336,343]`, ADR 0023):

```json
{
  "summary":  "1-2 plain-language sentences: what data comes in, what curated output comes out.",
  "trigger":  "what starts it, plain language (e.g. 'runs after the daily loan_master file lands').",
  "steps": [
    { "ordinal": 1,
      "title": "Ingest loan_master",
      "blueprintKey": "FileIngestion",        // INTERNAL — renders the canvas ghost; NEVER shown in user-facing text
      "medallionLayer": "bronze",              // bronze | silver | gold | control
      "description": "Pull the daily loan_master file into the bronze layer.",  // one sentence, non-technical
      "reasoning": "why this step / these params — surfaced from the op's REQUIRED reasoning field" }
  ],
  "additionalSpecs": [ "only non-obvious notes; NEVER secret values; NEVER deploy/promote instructions" ]
}
```

Then **interrupt for HITL** (n8n calls `interrupt()` after the planner; the user approves / modifies / rejects):

- **Approve** -> Apply Plan -> §7.9 atomic commit, ONE Command-Log transaction (the `composition.*` commands drain).
- **Modify** -> the correction re-enters the composer; the staging graph is REBUILT, not appended. (n8n's planner takes a
  `planFeedback` / `previous_plan` on a modify cycle `[read planner.prompt.ts buildPlannerContext]`; PULSE does the same
  — the staging clone is discarded and rebuilt.)
- **Reject** -> discard the staging graph; restore the snapshot (§7.8). No canonical / Command-Log write.

**The data-engineering divergences from n8n's planner output:**

1. **No `credentials` / `auth` anywhere.** n8n's `additionalSpecs` rule is "never mention credentials because the user
   already knows they need to connect accounts." PULSE goes further: there is NO credentials field AT ALL — secrets are
   SecretRefs resolved at build/package time, and the credential dialog (`request_credential_attach`) is the ONLY place a
   secret value is entered, never chat `[read PulseSystemPrompt:309,336,343]`. **SecretRefs-never-values is binding.**
2. **No deploy / promote in `additionalSpecs` or anywhere.** n8n's responder rule is "never tell the user to
   activate/publish their workflow." PULSE's is stronger and dev-only: never offer to "promote", "deploy to UAT /
   integration / production" — PULSE deploys to DEV ONLY; higher envs are enterprise CI/CD `[read PulseSystemPrompt
   ABSOLUTE_RULE #17]`.
3. **The step text stays non-technical** (no `blueprintKey`, no ids, no port names) — `blueprintKey` / `medallionLayer`
   are INTERNAL, used only to render the canvas ghost. This matches n8n's "Do not mention internal n8n node type names in
   steps."

> RESOLVED (operator 2026-06-16, DEFAULT): the per-step `reasoning` surfaced into the Plan Preview is PULSE's addition —
> n8n's plan steps carry `suggestedNodes` but NOT a per-step reasoning string `[read planner.prompt.ts]`. PULSE wires it
> from the REQUIRED `reasoning` on each mutating op (§1). The `previewData` keys (`summary`/`trigger`/`steps`/
> `additionalSpecs`) map onto the live `Plan.previewData` shape — reconcile against the live Plan Preview renderer at
> build time (no new contract; SPEC §7.2 RESOLVED).

---

## 4. Divergences found — the prior n8n prompt summary (since removed) vs the ACTUAL source

Per the STEP-1 instruction to "verify the n8n prompt summary against [the real source]; FLAG
divergences." That summary doc (`N8N-PROMPTS-REFERENCE.md`, since removed) was broadly ACCURATE — its verbatim quotes checked out — but the real source carries these
deltas (kept here as the record; this fragment now stands on the raw n8n source):

| # | The prior summary doc said | Actual source says `[read]` | Severity |
|---|---|---|---|
| 1 | §2 names the tool **`add_nodes`** and quotes `initialParametersReasoning` as `"REQUIRED: Explain your reasoning about initial parameters. Consider: Does this node have dynamic inputs/outputs? Does it need mode/operation/resource parameters?…"` | The `toolName` registered is **`add_nodes`** ✓ and the `.describe()` text matches verbatim ✓. BUT the summary OMITS that the tool carries a SECOND field `initialParameters` and an explicit "Provide both / Consider the initialParametersReasoning FIRST" instruction block + a long "INITIAL PARAMETERS" node table (`[read add-node.tool.ts]`). | Minor (incomplete, not wrong) — affects PULSE §1.1 split decision |
| 2 | §2 says `connect_nodes` "encodes the AI-reversed-direction contract + AUTO-CORRECT if backwards; multi-output indices." | ✓ Confirmed verbatim: `"Sub-nodes are ALWAYS the source… The tool will AUTO-CORRECT if you specify them backwards"` + the IF/Switch multi-output index rules + the `onError:'continueErrorOutput'` last-index error-output rules `[read connect-nodes.tool.ts]`. The summary UNDERSTATES how much of the description is the onError/error-output-index contract. | Minor — PULSE drops auto-correct (typed ports reject instead); logged in §1.1 |
| 3 | §2 says `update_node_parameters` "Runs a SEPARATE param-updater sub-LLM chain with its own wrapper" and quotes a description. | ✓ Confirmed: it calls `createParameterUpdaterChain(llm, …)` and the input is `changes: string[]` (NATURAL-LANGUAGE change strings, min 1), NOT a structured params object `[read update-node-parameters.tool.ts]`. The actual top-level description is: `"Update the parameters of an existing node in the workflow based on natural language changes. This tool intelligently modifies only the specified parameters while preserving others. Examples: …"` — matches the summary's paraphrase. | Confirmed — PULSE `set_params` deliberately takes STRUCTURED params (no sub-LLM); logged §1.1 |
| 4 | §1 / §2 list the discovery tools as **`search_nodes`**, **`get_node_details`**, **`get-documentation`**. | ✓ Tool NAMES confirmed: `search_nodes` (`node-search.tool.ts`), `get_node_details` (`node-details.tool.ts`), `get_documentation` (`get-documentation.tool.ts`). NOTE the FILE names are `node-search.tool.ts` / `node-details.tool.ts` (not `search-nodes`/`get-node-details`) — the summary's `search_nodes (by name OR by output connection-type)` is exactly right `[read node-search.tool.ts queryType: 'name' \| 'subNodeSearch']`. | Confirmed |
| 5 | §3 says `get-documentation.tool.ts` takes `type:"best_practices", techniques:[...]` and writes state `bestPractices`. | ✓ Confirmed it writes `stateUpdates.bestPractices` + `techniqueCategories`, and the schema is `{ requests: [...] }`. BUT there are **TWO** request types, not one: `best_practices` (with `techniques`) AND **`node_recommendations`** (with `categories`) — the summary omits the second `[read get-documentation.tool.ts]`. The techniques list in the actual planner prompt is LONGER (adds chatbot, content_generation, data_extraction, triage, monitoring, enrichment, knowledge_base, human_in_the_loop, data_analysis, …) than the 15-item list in the tool. | Minor (incomplete) — does not affect PULSE (5 category guides, not techniques) |
| 6 | §1/§5 quote the Planner role + the `{summary, trigger, steps, additionalSpecs}` output. | ✓ Confirmed verbatim against `planner.prompt.ts` ROLE/GOAL/RULES/OUTPUT_FORMAT. The actual `additionalSpecs` text is STRONGER than the summary's paraphrase: `"NEVER mention API keys, credentials, connecting accounts, or authentication — these are always required and stating them wastes the user's time."` Also the real planner has a `web_fetch_tool` section + a `<modification_mode>` block (planFeedback/previous_plan) the summary doesn't mention. | Minor (incomplete) — PULSE drops web_fetch (§7.18 row 28); keeps modify-cycle |
| 7 | §4 lists the context tags including `<execution_status>` with `success\|error\|issues_detected\|no_execution` + `<data_flow>` + `<nodes_with_empty_output>`. | ✓ Confirmed ALL verbatim in `buildSimplifiedExecutionContext` / `buildDataFlowString` — including the `<nodes_not_executed>` tag the summary half-omits, and the exact `"Node1 (5 items) → Node2 (0 items)"` data-flow format `[read context-builders.ts]`. | Confirmed |
| 8 | §1 says prompts live in `prompts/agents/` as 5 per-stage agents (supervisor/discovery/builder/planner/responder). | ✓ `prompts/agents/planner.prompt.ts` confirmed present and current (a 2026-02-11 commit `#25617` modified it to add `selectedNodesContext`). The tool registry is `tools/builder-tools.ts` + `subgraphs/builder.subgraph.ts` `[read via Exa code-search]`. | Confirmed — repo structure is current |

**Net:** the summary doc has NO factual errors in its verbatim quotes; its gaps are all **omissions of completeness**
(the second `add_nodes` field, the second `get_documentation` request type, the `<nodes_not_executed>` tag, the planner's
`web_fetch_tool` + `<modification_mode>` sections, the stronger `additionalSpecs` wording). The two deltas that MATTER for
PULSE design are **#1** (PULSE splits add+params; n8n couples them) and **#3** (PULSE `set_params` is structured;
n8n's is a natural-language sub-LLM) — both already reflected in §1.1 above. None of the omissions change a §7.19 contract;
they refine it.

---

## 5. Authored-here tally

- **Op-emitting tool contract strings (Build/Composer + Configure):** 7 mutation-tier (`add_blueprint_instance`,
  `wire_ports`, `set_params`, `remove_instance`, `remove_wire`, `rename_instance`, `set_pipeline_setting`) + 8
  discovery-tier = **15 strings**, each mutation string carrying the REQUIRED `reasoning` contract (n8n
  `initialParametersReasoning` analogue, `[read]`). `set_params` re-confirmed STRUCTURED (no sub-LLM; §1.1, §4 row 3).
- **Provision-tier tool contract strings (NEW — §1.2):** **13 strings** surfaced verbatim from the live backend —
  `list_data_sources` / `create_data_source` / `list_domains` / `create_domain` / `list_connectors` /
  `get_connector_type_schema` / `create_connector` / `get_storage_paths` / `request_credential_attach` /
  `list_datasets` / `create_dataset` (SoR/Domain/Connector/Dataset CRUD), `derive_dataset_schema` /
  `create_dataset_from_discovery` (sample-data / schema-inference), plus `list_sink_targets` / `create_sink_target`
  (sink-target provisioning) = **15 total Provision tools** — all `[read ChatTools.java` + executor `:247-300]`,
  elicitation discipline `[read PulseSystemPrompt.java]`.
- **Context-injection wrappers:** 6 composition tags (`<current_composition>`, `<dataset_schemas>`, `<selected_step>`,
  `<schema_visibility>`, `<conflict_overlay>`, `<run_status>`) + the plain-text conversation-summary block + **3 NEW**
  (`<runtime_mode>` Mode/persona constant — §2.1; `<blueprint_config_guidance>` per-param guidance for Build/Configure —
  §2.2; `<session_facts>` structured short-term memory — §2.2, f22) = **10 injections**.
- **Plan-mode output:** **1** structure (the Plan Preview JSON + approve/modify/reject HITL gate, no credentials, no
  deploy/promote).
- **Divergences flagged:** **8 rows** (§4) verifying the summary doc against the actual source.
- **`> GUESS:` flags — ALL RESOLVED (operator 2026-06-16):** the composition tool NAMES are snake_case (live convention,
  0 hyphens); `add_blueprint_instance` carries **NO initial params** (always a separate `set_params` — 3-F4); the
  `<dataset_schemas>` / `<schema_visibility>` / `<conflict_overlay>` tags are the contract (ADR 0011 + §2 overlay); the
  per-step `reasoning` and `previewData` key mapping are DEFAULT (reconcile at build time); the Provision-tier **stage
  assignment** is §F's decision with allow-lists derived mechanically from tier (3-F3); the `<runtime_mode>` /
  `<blueprint_config_guidance>` tag text + field sets are the proposed contract (decisions `[read]` changelist §E; wording
  is the operator-voice pass — §2.1/§2.2).

Everything else is `[read]` from the actual n8n source (cited by file), `PulseSystemPrompt.java` (cited by rule/line),
`ChatTools.java` / `ChatToolExecutor.java` (the live tool surface + executor wiring), `RuntimeAuthorityService.java` /
`TenantRuntimeReadinessService.java` / `RuntimePersona.java` (the Mode/persona + storage mapping), or
ADR 0001/0011/0012/0013/0023/0024.
