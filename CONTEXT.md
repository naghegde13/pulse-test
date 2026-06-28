# PULSE

PULSE is the dev-time tool a data engineer uses to **design** a data pipeline, **build** it into runnable code, **package** it, and **deploy** it to a runtime — either Google's cloud (Composer + Dataproc) or the on-prem Cloudera data center (Airflow + Spark). PULSE itself only lives in the dev environment; the higher environments (integration, UAT, prod) have no PULSE — they just run what was deployed to them.

## Language

### What PULSE is made of (the five components)

PULSE is four components in a line — Designer, Builder, Packager, Deployer — plus the Chat that can drive the Designer, plus two small helpers.

**Designer**:
Where the customer says what pipeline they want. Two ways in: talking to the Chat in plain English, or clicking blueprints together by hand on a visual canvas. Either way the result is the same: a picture of the pipeline as a chain of blueprints.
_Avoid_: "pipeline builder", "composition UI" (those are screens inside the Designer, not the Designer itself).

**Builder**:
Takes the pipeline the customer designed and turns it into the actual code that will run — PySpark for the ingest/bronze step, dbt (SQL) for the silver and gold steps, Great-Expectations checks for data-quality gates. (This is what the code currently calls "codegen.")
_Avoid_: "Compiler" (older internal word for the same thing), "codegen" alone.

**Packager**:
Takes the code the Builder produced and wraps it into one sealed, signed bundle that can be shipped to any environment (dev, integration, UAT, prod) within the active Mode. The bundle carries the per-environment settings it needs, but never the actual secret values — only pointers to them.
_Avoid_: "env-stamped package" (the bundle is environment-agnostic and carries **all** environments' config slices; at run time the **runtime** selects the active env's slice via a configured environment variable — the Deployer ships the whole bundle, it does NOT pick a slice), "build".

**Deployer**:
Takes the sealed bundle and ships it **whole** (all config slices) to one specific target environment; the runtime there then **selects that environment's slice** of the bundled settings, using a configured environment variable that identifies which environment it is. The Deployer does not pick a slice — it deploys the entire env-agnostic bundle. PULSE only lives in dev; the Deployer is how dev pushes a pipeline outward.
_Avoid_: "promote" (a promotion is: build a new bundle + deploy; a deploy alone is one ship).

**Chat**:
The plain-English assistant that drives the Designer for the customer — they describe what they want, and the Chat lays out and configures the blueprints for them. The customer or a developer can always step in and adjust by hand. Chat is impressive in demos; how far it can carry a full pipeline by itself is still being checked.
_Avoid_: "the LLM" alone (the LLM is the engine; Chat is the component).

### The two helpers

**Schema Propagation**:
The helper that works out, for every step in the pipeline, what columns come in and what columns go out, and carries that shape down the chain — so the customer can see the data's shape at each step and catch mismatches before anything runs. It uses **schema inference** (below) to compute one step's output columns from its input.
_Avoid_: "schema graph" (that's one screen that shows it).

**Expression Builder**:
The small helper that checks the little formulas a customer types into a blueprint — a derived-column formula like `balance * 1.05`, or a filter like `status = 'ACTIVE'`. It makes sure the formula is valid and only references columns that actually exist at that point in the pipeline.
_Avoid_: "Builder" (unrelated to the Builder component above — this one only checks formulas).

### The things they work on

**Blueprint**:
A Lego block — one reusable building unit in the catalog that the customer drops onto a pipeline. **Blueprints come in two kinds, and both are blueprints:** a **data blueprint** that moves data (it has ports and carries a plain-English description of the data operation it performs plus example code), and a **Pipeline Setting** that moves no data but provisions a pipeline-wide behavior (schedule, triggers, rollback). Either kind is a node the Builder must emit real code or config for; both the Chat (laying the pipeline out) and the Builder (writing the code) read the description and examples. A catalog entry that emits nothing (a pure FinOps or deploy-lifecycle concern) is not a blueprint at all. Adding a new blueprint to the catalog should be easy.
_Avoid_: "template", "module", "step". Never oppose "blueprint" and "Pipeline Setting" — a Pipeline Setting **is** a blueprint.

**Operation (op)**:
The primitive unit a blueprint's behavior is built from. A data blueprint declares an **ordered list of ops** (e.g. `join` → `keep-columns`); each op self-describes its effect on columns, rows, and any side-output. The op set is **closed and small** (~28: add-column, drop-columns, join, group-and-aggregate, filter-rows, deduplicate, mask-columns, …; full list + per-blueprint decomposition in ADR 0012 / `docs/blueprints/OP-VOCABULARY-AND-DECOMPOSITION.md`). The Builder reads the op-list (metadata) to emit code and infer schema — it does **not** hand-write a generator per blueprint, and **no op is named after a blueprint**.
_Avoid_: naming an op after a blueprint (that's a rename, not a primitive); a free-form op "recipe" (the set is closed).

**Port**:
Where data enters or leaves a blueprint. Blueprints that move data have input and output ports; blueprints that don't move data (a scheduler, a freshness check) have no ports — which is why their place in the run-order isn't settled by data wiring and needs separate handling.
_Avoid_: "input/output" alone.

**Pipeline Setting** (orchestration policy):
A **kind of blueprint** (not a separate or competing construct) that moves no data and has no ports, but provisions a behavior or setting on the whole pipeline — its schedule and triggers, or "undo the run's data operations if it fails." It is configured in the pipeline's orchestration panel and may sit on the DAG as an unconnected node, but it is still a blueprint on the composition surface — never off-surface config. Distinct from a cross-cutting platform concern (cost/FinOps, deploy lifecycle), which provisions no pipeline behavior and is therefore not a blueprint at all.
_Avoid_: "policy blueprint" alone, "config blueprint"; do not treat it as an alternative to "blueprint".

**Schema inference**:
Working out the columns coming out of one blueprint from the columns going in plus how that blueprint is configured. This is **100% deterministic — the LLM has no role in computing a schema** (ADR 0011): the answer comes from a fixed rule, the blueprint's **declared schema behavior**, or **schema discovery** (sampling the source). Those resulting columns are the **enforced contract** the generated code must produce. (The LLM's only home in the Builder is writing the transform *body*, constrained to this schema.) Today the code still has an LLM fallback for unknown blueprints and the generator doesn't yet enforce the contract — both known problems to fix.
_Avoid_: "schema detection", "auto-schema", "hybrid static + AI schema" (the AI half is being removed).

### Mode and the two substrates

**Mode**:
The one big installation-level choice: PULSE runs in **GCP mode** or **DPC mode**, never both at once. The two are different enough — different storage, different table format, different orchestrator — that the Builder effectively has to be two builders, producing genuinely different pipeline code for each.
_Avoid_: "deployment target" (a target is one environment inside a Mode).

**GCP mode**:
Google's cloud. Composer (managed Airflow) runs the jobs; Dataproc runs Spark; data lives in GCS; tables use **Iceberg**; BigQuery is the warehouse. This is a **lakehouse**: bronze + silver are Iceberg tables on the lake, gold is native BigQuery. (**Target = BigQuery-managed Iceberg; today PULSE ships the Iceberg-on-GCS *interim*** — ADR 0007; the managed target is not live yet.) Iceberg gives transactions and flexibility.

**DPC mode** (Cloudera, on-prem):
The on-prem data center. Plain Airflow runs the jobs; Cloudera Spark runs the compute; data lives in S3-compatible storage. **Today the tables are Hive + Parquet only** — a plain **data lake**, append-only style, with limited transaction support. (The code also contains Iceberg support for DPC, but the real Cloudera environment won't have Iceberg for roughly 9–12+ months, so that code is dormant.) This Hive+Parquet-vs-Iceberg gap, plus lake-vs-lakehouse and plain-Airflow-vs-Composer, is why a pipeline must be built differently per Mode.

### Two words to keep straight

**Functional (from the customer's point of view)**:
The customer can complete a whole journey — design a pipeline, build it into code, package it, deploy it to dev, and see its output. None of the five components delivers a customer-visible outcome end-to-end today.
_Avoid_: "built", "working", "ready", "GREEN" (these have all meant "some code exists", not "the customer can finish the journey").

**Customer**:
A data engineer — the person PULSE is for.
_Avoid_: "user", "developer".
