# Materialize at medallion boundaries (forced); fuse intermediate steps in memory

Status: accepted

A pipeline of N design steps must not become N tables. Within a layer, consecutive same-engine steps are **fused in memory** — chained Spark DataFrames inside one PySpark job, or dbt `ephemeral` models (CTEs) — and write no intermediate table.

The system **forces** a persisted table at every **medallion boundary (bronze, silver, gold)**. This is not left to the developer: those boundaries are the contract and hand-off points that other pipelines, consumers, and lineage depend on, so a table must always exist there. A table is also materialized at a **data-quality gate** and wherever the pipeline **crosses engines** (PySpark → dbt). Everywhere else, fuse.

Trade-off: persisting every step would be ruinous I/O and storage; but leaving persistence entirely to the developer would let medallion contracts silently vanish. Forcing persistence only at the boundaries (plus gates and engine crossings) buys both efficiency and a stable contract surface. The Builder must therefore group consecutive same-engine, same-layer steps into one job/model and emit a materialized table only at these forced points.

## Refinement (2026-06-15): three materialization tiers, not two

"Persist" splits into two kinds, so there are **three** tiers:
1. **Ephemeral** — fused as a CTE, no table. Default for intermediate same-layer steps.
2. **Temp** — materialized **for performance** within a complex chain (e.g. a long SQL-chaining blueprint where a single CTE query would be too slow/large). Internal and cleanable; **NOT a contract/lineage table.**
3. **Real** — a persisted **contract** table, FORCED at medallion boundaries / DQ gates / engine crossings. Durable and lineage-bearing.

So "a real table is forced at the boundaries" means **tier 3 specifically**; temp tables (tier 2) are an optimization, never a contract. For the **SQL-chaining blueprint** (ADR 0013), dbt does not auto-optimize materialization, so the power-user specifies **per-chain-element** materialization (ephemeral vs temp) via that blueprint's config panel; tier-3 real tables remain forced by the medallion rule regardless of the user's choice.
