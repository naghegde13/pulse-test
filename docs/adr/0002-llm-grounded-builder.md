# The Builder generates code with an LLM grounded in each blueprint's examples, over a deterministic skeleton

Status: **⛔ SUPERSEDED by ADR 0013 (2026-06-15)** — ADR 0012's closed op-set made per-op deterministic codegen scale, so the LLM is removed from codegen entirely (LLM → Chat-side assistant only). The original decision + the under-revision notes below are kept for history. [Originally: accepted — **UNDER REVISION (2026-06-13 grill).**] The deterministic/LLM boundary is being refined: the LLM's freedom includes the **implementation form** (dbt SQL *or* procedural PySpark *or* a UDF — not just a SQL body); structural values (paths/format) are **config-externalized** per environment in the package, not hardcoded; **DDL derives from the inferred schema** (← schema inference / S1). The full rework is a **dedicated broad-refactor grill-with-docs session** (operator-mandated; orchestrator brings recommendations). See `docs/anchor/BUILDER-ARCHITECTURE-GRILL.md`.

Blueprints are defined by a plain-English description plus example code, and the catalog is meant to grow by adding blueprints (description + examples) without a developer hand-writing a code generator for each one. Deterministic templating cannot honor that — it needs a hand-written emitter per blueprint and reads neither the description nor the examples — so the Builder feeds an LLM each in-scope blueprint's description, example code, the resolved input/output columns, and the target Mode, and has it write that blueprint's body in the right language for its layer:

- **PySpark** for ingestion / bronze,
- **dbt SQL** for silver and gold,
- **Great-Expectations (Python)** for data-quality gates.

The structural decisions stay deterministic and machine-enforced — where tables and files physically live, table DDL, partitioning, GCP-vs-DPC shape, and DAG wiring (the path-convention, storage-format-rules, and runtime-authority helpers). Mode-correctness is never left to the model; the LLM only fills the per-blueprint logic inside that skeleton, checked afterward by a forbidden-token scan and a schema/repair loop.

Considered options:
- **Pure templating** (what exists today, a basic placeholder) — rejected: can't honor description+examples blueprints, and every new blueprint would need a developer to hand-write a generator, breaking "adding a blueprint should be easy".
- **Pure LLM, "generate the whole pipeline"** — rejected: it would let the model decide storage paths, DDL, and partitioning, where a single hallucination ships a broken or Mode-violating pipeline. The deterministic skeleton exists precisely to take those decisions away from the model.
