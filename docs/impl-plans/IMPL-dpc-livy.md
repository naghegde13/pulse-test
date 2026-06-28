# IMPL PLAN — DPC / Apache Livy emission path

> **Type:** IMPLEMENTATION (build) plan — NOT code. This sequences the work, names the
> prerequisites, defines the tests/milestones, and flags the one piece that is **not yet
> zero-fuzziness** and must get a gated mini-spec BEFORE any of its code is written.
>
> **Backlog item:** `docs/PULSE-MAP.md` → `## LIVE TO-DO` → **REQUIRED follow-ups** →
> "DPC Spark → Apache Livy (ADR 0006)" `[read]` PULSE-MAP.md:225-228.
> **Target contract:** `docs/build-specs/SPEC-codegen-compiler.md` §C.2 (the Mode×engine
> emission matrix, G-13) `[read]` SPEC-codegen-compiler.md:127-170.
> **Standard the build is proven to:** byte-exact, deterministic (ADR 0009) `[read]`
> SPEC-codegen-compiler.md:323-371 (§E oracle).
> **Handoff discipline:** ADR 0008 (own worktree + evidence doc + independent evaluator)
> `[read]` PULSE-MAP.md:181.

---

## 0. GROUND TRUTH — what is built today vs. what this plan builds

> **Citation shorthand:** bare `CodeGenerationService.java:NNN` refs below are the full path
> `backend/src/main/java/com/pulse/codegen/service/CodeGenerationService.java` (likewise
> `CanonicalLoanMasterAirflowRuntimeIT.java` = `backend/src/test/java/com/pulse/e2e/runtime/…`).

The §C.2 matrix has two columns. **The GCP/Dataproc column is the only built path; the DPC
column "remains to be built" (G-13).** Verified in source on this branch
(`guided/acme-lending-human-rehearsal-2026-05-25`):

- The DAG emitter still emits a **plain `SparkSubmitOperator`** (`conn_id='spark_default'`),
  which §C.2/ADR 0006 say is the target for **neither** Mode `[read]`
  CodeGenerationService.java:585 (and the secret-binding variant :424). The Dataproc operator
  (`DataprocCreateBatchOperator`, the built GCP half) is **not on this branch** — it lives on
  the parked `g1-builder-gcp-correct` worktree (G1, unit-passed, unmerged) `[read]`
  PULSE-MAP.md:254. So **on `main`/this branch the only Spark-submit emission that exists is
  the legacy `SparkSubmitOperator`**, and neither the GCP nor the DPC target shape is present.
- The bronze write is **hardcoded `USING DELTA` / `.format('delta')`** — Mode-blind, the old
  default `[read]` CodeGenerationService.java:1141,1146. §C.2 C-2 says DPC bronze/silver =
  **Hive + Parquet**, and Delta is the format for **neither** Mode `[read]`
  SPEC-codegen-compiler.md:137-146.
- Mode is resolvable today via `RuntimeAuthorityService.getAuthority().allowedStorageBackends()`
  `[read]` CodeGenerationService.java:148-149 — i.e. the branch point the matrix keys on
  already exists; what's missing is the DPC-shaped emission behind it.
- A **local DPC-flavoured runtime harness already runs end-to-end** today
  (`CanonicalLoanMasterAirflowRuntimeIT`: seeds a local DPC storage backend on MinIO/S3a,
  generates → bridges → renders → runs) `[read]`
  CanonicalLoanMasterAirflowRuntimeIT.java:97,145. **BUT** it asserts the OLD Delta + plain
  `SparkSubmitOperator` shape, **not** the Hive+Parquet+Livy target. So it is a starting
  scaffold for the DPC anchor, **not** the anchor — it proves the old shape runs, not that the
  spec's shape is byte-correct.

**Net:** this is a genuine build, not a rewire-of-an-existing-path. The matrix's *target* is
locked; the *DPC emission code* and a *DPC oracle* do not exist.

---

## 1. SCOPE — the three pieces (from the backlog entry)

The backlog entry decomposes into exactly three pieces `[read]` PULSE-MAP.md:225-228. Pinned
here with their §C.2 sub-targets so each is independently verifiable.

### Piece (1) — BUILD the DPC emission path
Wire the per-engine §C.2 matrix into codegen for Mode `DPC_PULSE`, **byte-identical to the
spec** `[read]` PULSE-MAP.md:226 + SPEC-codegen-compiler.md:161-170. The five engine rows:

| Engine | DPC_PULSE target (what piece 1 emits) | Source anchor |
|---|---|---|
| **PySpark** | Apache Livy **batch submit**; bronze/silver write = **Hive + Parquet** (replaces `USING DELTA` / `.format('delta')`) | SPEC §C.2:146,163; today CodeGenerationService.java:1141,1146,585 |
| **dbt** | adapter target = **Cloudera Spark profile via Livy/Thrift**; catalog = **Hive metastore**; **`file_format='parquet'`** (replaces the `delta`/`pulse_delta_table` materialization branch) | SPEC §C.2:164; today CodeGenerationService.java:1711-1766,2925-2968 |
| **GX** | checkpoint store on **S3-compatible storage**; quarantine/report side-tables written as **Hive + Parquet** | SPEC §C.2:165 |
| **dbt-snapshot** | snapshot store = **Hive metastore**; `{% snapshot %}` materializes **Parquet** | SPEC §C.2:166; today CodeGenerationService.java:2109-2248 |
| **DAG / Airflow** | runs on **plain Airflow** (not Composer); **plain-Airflow operator flavors** (Livy / HDFS / S3-compatible) | SPEC §C.2:167; today the DAG emitter CodeGenerationService.java:356-616 |

Mode is read from `RuntimeAuthorityService` (§C.2:135) — the matrix is a **branch on the
resolved Mode**, applied per-engine. The GCP half is untouched by this piece.

> **DEPENDENCY (not in this plan's code):** the per-engine handlers piece (1) wires up are the
> **Builder (#1/#2) DPC emission handlers** — the same op×engine handlers being (re)built in
> the ACTIVE-PHASE build specs (lanes C/D/E/F in `## LIVE TO-DO`) `[read]` PULSE-MAP.md:206.
> Piece (1) is the **Mode-`DPC_PULSE` branch** of those handlers, not a separate emitter. If
> the underlying handler doesn't exist/settle yet, piece (1) has nothing to branch. See §3.

### Piece (2) — PIN the Livy submission CONTRACT  ⚠️ NEEDS A GATED MINI-SPEC BEFORE CODING
§C.2 settles the *target* ("Livy **batch submit**") but **the mechanics are NOT yet
zero-fuzziness** `[read]` PULSE-MAP.md:227. The open mechanics, each a fuzziness the mini-spec
must kill:

- **Submission mechanism** — Airflow `LivyOperator` (provider
  `apache-airflow-providers-apache-livy`, params `file`/`class_name`/`args`/`conf`/`proxy_user`/
  `livy_conn_id`/`polling_interval`) **vs.** raw `POST /batches` REST against the Livy gateway.
  Two viable mechanisms = a real fork the spec must choose. (Web-confirmed: the operator wraps
  the batch REST API; `LivyOperator(file, class_name, args, conf, livy_conn_id='livy_default',
  polling_interval=…)`.)
- **Batch-payload shape** — the exact JSON: `file` (the job `.py`/jar path), `pyFiles`,
  `args`, `conf` (Spark props), `proxyUser`, `name`, `queue`, driver/executor sizing. Which
  fields PULSE emits vs. leaves to the Livy connection/per-env config slice.
- **Poll / callback** — `polling_interval` value; terminal-state set (`success`/`dead`/
  `killed`) → Airflow task success/failure mapping; how the PULSE Airflow→API callback
  (existing pattern) fires on Livy terminal state.
- **`conn_id` / connection config** — the `livy_conn_id` name (`livy_default`? a PULSE
  convention?), host/port/auth, and how it is supplied via the §C.3 per-env config slice
  (`PULSE_ENV` → `config/<env>.yaml`) `[read]` SPEC-codegen-compiler.md:178-184 — NO literal
  endpoints baked in code.
- **Error handling** — submit failure, lost session, Livy-gateway down, batch timeout; retry
  policy and idempotency (re-submit safety).

**This piece's deliverable is a SPEC, not code.** It is grilled to zero-fuzziness and
SPEC-GATE-passed (per the spec-discipline trial) BEFORE piece (1)'s PySpark/DAG-Livy code is
written. Piece (1)'s DAG-Airflow and PySpark rows **consume** this contract.

### Piece (3) — AUTHOR a DPC byte-exact ANCHOR
**No DPC anchor exists today** — only the GCP anchor (`CanonicalLoanMaster…`, the `loan_master`
/ `tenant-home-lending` / GCS→bronze→silver oracle) `[read]` PULSE-MAP.md:228 +
SPEC-codegen-compiler.md:325-327. Without a DPC anchor the DPC path **cannot be proven to the
ADR-0009 byte-exact standard** `[read]` PULSE-MAP.md:228. Piece (3) authors:

- a **DPC-flavoured anchor pipeline + oracle** (same `loan_master` business data so the
  business-column byte-diff reuses the verified 242/242 answer key
  `[read]` SPEC-codegen-compiler.md:341), but asserting the **Hive+Parquet** physical shape and
  **Livy** submission instead of Delta/SparkSubmit;
- following the **ADR-0009 method** (author the deterministic reference output; generate the
  Builder's code 2–3×; run each; assert every output byte-identical to each other AND to the
  reference; normalize runtime-only audit columns out of the diff) `[read]`
  SPEC-codegen-compiler.md:329-335,368-371.
- It **reuses the existing local DPC runtime IT scaffold** (`CanonicalLoanMasterAirflowRuntimeIT`
  already seeds a local DPC backend + materialize/render/run bridge) `[read]`
  CanonicalLoanMasterAirflowRuntimeIT.java:97,129-158 — re-pointed at Hive+Parquet+Livy.
- This anchor is the **oracle that validates piece (1)** `[read]` PULSE-MAP.md:228.

---

## 2. PREREQUISITES (gating; each blocks a specific piece)

| # | Prerequisite | Gates | Why |
|---|---|---|---|
| **P1** | **Gated Livy-submission mini-spec** (piece 2) grilled to zero-fuzziness + SPEC-GATE pass | the **code** of piece (1)'s PySpark + DAG-Livy rows, and piece (3)'s Livy assertions | §C.2 fixes only the *target*; mechanics are fuzzy `[read]` PULSE-MAP.md:227. Zero-fuzziness-before-work is operator-mandated (memory: zero-fuzziness-spec-gate). Coding the Livy payload before this = guaranteed rework. |
| **P2** | **Builder (#1/#2) DPC emission handlers exist/settle** (the op×engine handlers, ACTIVE-PHASE lanes C/D/E/F) | piece (1) | Piece (1) wires the **Mode-`DPC_PULSE` branch** of those handlers; there must be a handler to branch `[read]` PULSE-MAP.md:206,226. |
| **P3** | **A DPC test environment** (Livy gateway + Hive metastore + S3-compatible storage; local container stack acceptable for the byte-exact run, mirroring today's MinIO harness) | piece (3) (and the integration run of piece 1) | A byte-exact anchor must actually RUN the generated job; the GCP anchor needs Composer/Dataproc, the DPC anchor needs a Livy+Hive+Parquet runtime `[read]` PULSE-MAP.md:228. Today's IT runs MinIO+Spark+Delta locally `[read]` CanonicalLoanMasterAirflowRuntimeIT.java:97 — extend it (add Livy + Hive metastore + Parquet), don't build from zero. |

**Prerequisite count: 3** (P1 spec-gate · P2 Builder DPC handlers · P3 DPC test environment).

---

## 3. BUILD PHASES (ordered)

> Ordering principle: the **spec (P1) is serial and first** (it gates code); everything
> downstream of a locked spec fans out (PULSE-MAP.md:183). The anchor scaffold (piece 3) can be
> staged in parallel with the spec since its *runtime plumbing* (P3) doesn't depend on the Livy
> payload shape — only its *assertions* do.

**Phase 0 — Pre-flight / confirm ground truth (no new code).**
Confirm on the integration branch: which Spark-submit shape is present (legacy
`SparkSubmitOperator` only, until G1 merges the GCP half), that `RuntimeAuthorityService` Mode
resolution is the live branch point (CodeGenerationService.java:148), and that the Delta
hardcodes (1141,1146 + the dbt `delta`/`pulse_delta_table` branches 1711-1766/2925-2968/
2109-2248) are the exact sites piece (1) edits. Produces the edit-site inventory.

**Phase 1 — Gated Livy-submission mini-spec (P1).  ⚠️ SPEC, NOT CODE — and it is the gate.**
Grill piece (2) to zero-fuzziness: choose the submission mechanism (LivyOperator vs raw
`POST /batches`), pin the batch-payload field set, poll/terminal-state→task mapping, callback
wiring, `livy_conn_id` convention + per-env config-slice plumbing (§C.3 `PULSE_ENV`), and
error/retry/idempotency. Run SPEC-GATE (the `spec-gate` skill, fresh independent agent). **No
piece-(1) Livy code starts until this passes.** Serial on operator grill time.

**Phase 2 — DPC anchor scaffold (piece 3, runtime half) — PARALLEL with Phase 1.**
Stand up the DPC test environment (P3): extend the existing `CanonicalLoanMaster…` IT harness
to add a Livy gateway + Hive metastore + Parquet write path alongside today's MinIO/S3a backend.
Author the **deterministic DPC reference oracle** (Hive+Parquet physical shape; reuse the
verified `loan_master` 242/242 business answer key for the byte-diff). Leaves the **Livy
submission assertions stubbed** until Phase 1 locks (the only part that depends on P1).

**Phase 3 — Emit Hive+Parquet (piece 1, format half) — after P2, PARALLEL with Phase 1.**
The format swap does **not** depend on the Livy contract, so it runs as soon as the Builder DPC
handlers (P2) are in place:
- PySpark bronze/silver write: Mode-`DPC_PULSE` → Hive+Parquet (replace `USING DELTA` /
  `.format('delta')`, CodeGenerationService.java:1141,1146).
- dbt: Mode-`DPC_PULSE` → `file_format='parquet'` + Hive-metastore catalog + Cloudera-Spark
  profile (the `delta`/`pulse_delta_table` materialization branches 1711-1766, 2925-2968).
- GX side-tables + dbt-snapshot store → Hive+Parquet (2109-2248).
GCP/Iceberg half untouched; gated behind the resolved Mode.

**Phase 4 — Emit Livy submission (piece 1, submit half) — after Phase 1 (P1) AND Phase 3.**
Implement the DAG-Airflow + PySpark Livy rows to the **locked Phase-1 contract**: emit the
chosen Livy mechanism (operator/REST) with the pinned payload, poll/callback, `livy_conn_id`,
error handling; plain-Airflow operator flavors. This is where `SparkSubmitOperator`
(CodeGenerationService.java:585) is replaced for the DPC branch. Wire `livy_conn_id`/endpoints
through the §C.3 per-env config slice (no baked literals).

**Phase 5 — Wire anchor assertions + prove byte-exact (piece 3 ∩ piece 1).**
Un-stub the Phase-2 anchor's Livy assertions against Phase-4 output. Run the ADR-0009 loop:
generate 2–3×, run each on the P3 environment, assert byte-identical across runs AND vs the DPC
reference oracle. **This is the proof gate for piece (1).**

**Phase 6 — ADR-0008 handoff + evaluator + integrate.**
Implementer in its own worktree writes `docs/evidence/dpc-livy-EVIDENCE.md` (its claim); a
**fresh evaluator agent re-runs** the DPC anchor on the P3 environment and returns the verdict;
merge only on evaluator PASS `[read]` PULSE-MAP.md:181. Update §C.2's "remains-to-be-built"
flag → built; tick PULSE-MAP.md:225-228.

**Phase count: 7** (Phase 0 pre-flight through Phase 6 handoff).

---

## 4. TESTS

- **New DPC byte-exact anchor** (piece 3) — the primary deliverable test. `loan_master` DPC
  variant; asserts bronze EXISTS Hive+Parquet (500 rows × 86 cols = 78 source + 8 audit) and
  silver (290 rows × 78 business cols, `filter-rows(loan_status="Current")` of Cleaning), audit
  columns normalized out of the byte-diff — same answer key as the GCP anchor, different
  physical engine `[read]` SPEC-codegen-compiler.md:343-366.
- **Byte-exact parity (ADR-0009 method)** — generate the Builder's DPC code **2–3 times**, run
  each on the P3 Livy+Hive+Parquet environment, assert every output byte-identical to each
  other AND to the deterministic DPC reference oracle; any divergence = FAIL = a real bug;
  deterministic tiebreakers (explicit ORDER BY on any dedup/ranking) `[read]`
  SPEC-codegen-compiler.md:329-335,368-371.
- **GCP-anchor non-regression** — re-run the existing GCP anchor to confirm the
  Mode-`DPC_PULSE` branch did not perturb the `GCP_PULSE` path (the matrix must branch, not
  replace).
- **Codegen unit deltas** — `CodeGenerationServiceTest`: assert DPC Mode → no `delta` literal,
  no plain `SparkSubmitOperator`; emits the Livy mechanism + `file_format='parquet'`
  (the mirror of G1's GCP assertions `[read]` PULSE-MAP.md:254).
- **Forbidden-token scan** — extend the existing `ForbiddenTokenScanner` so a `DPC_PULSE`
  artifact containing `DELTA`/`SparkSubmitOperator` fails the build `[read]` PULSE-MAP.md:254.

---

## 5. MILESTONES

1. **M1 — Livy contract LOCKED.** Phase-1 mini-spec SPEC-GATE PASS (piece 2 done). *Unblocks
   all Livy code.*
2. **M2 — DPC test environment GREEN.** Phase-2 harness runs the existing pipeline end-to-end
   on Livy+Hive+Parquet (old shape acceptable) — proves the P3 plumbing before correctness.
3. **M3 — Hive+Parquet emission.** Phase-3 format swap: DPC artifacts write Parquet, zero
   `delta` literals (piece 1, format half).
4. **M4 — Livy submission emission.** Phase-4: DPC DAG submits via Livy, zero plain
   `SparkSubmitOperator` (piece 1, submit half).
5. **M5 — DPC ANCHOR PASSES byte-exact.** Phase-5 ADR-0009 loop green (piece 3 validates piece
   1). *The real proof.*
6. **M6 — MERGED.** Phase-6 independent evaluator PASS; §C.2 G-13 flag flipped to "built";
   PULSE-MAP.md:225-228 ticked.

---

## 6. FAN-OUT (parallelism — PULSE-MAP.md:183-186)

- **Serial (operator time):** only Phase 1 (the Livy mini-spec grill) — one human, one spec.
- **Parallel from the start:** Phase 2 (DPC anchor runtime scaffold + P3 environment + the
  Parquet reference oracle) runs alongside the Phase-1 grill — its plumbing doesn't need the
  Livy payload shape, only its assertions do.
- **Parallel after P2 (Builder handlers):** Phase 3 (Hive+Parquet format swap) — independent of
  the Livy contract.
- **Convergence:** Phase 4 (Livy submit) needs BOTH M1 (locked contract) and Phase 3; Phase 5
  joins piece (1) ⨉ piece (3).
- **External agents (Codex/Droid):** once M1 locks, Phases 3 and 4 are self-contained, spec-in-
  hand codegen tasks — prime candidates to farm out with the spec + fixtures + the
  behavioral-test contract, **withholding the evaluator oracle** (ADR 0004/0008) `[read]`
  PULSE-MAP.md:186,189.

---

## 7. RISKS

- **R1 — Coding piece (2)'s mechanics before the spec locks.** The single biggest risk; the
  backlog explicitly flags the mechanics as not-yet-zero-fuzziness `[read]` PULSE-MAP.md:227.
  *Mitigation:* P1 is a hard gate; Phase 4 cannot start until SPEC-GATE passes. (Memory:
  zero-fuzziness-spec-gate — fuzzy specs are PULSE's chronic slop.)
- **R2 — Submission-mechanism fork (LivyOperator vs raw REST).** Two viable mechanisms; picking
  wrong forces a rewrite of every DAG emission. *Mitigation:* it is an explicit decision in the
  P1 spec, recorded as an ADR.
- **R3 — Piece (1) blocked on the Builder DPC handlers (P2).** Piece (1) is a *branch* of
  handlers the ACTIVE-PHASE build specs are still producing; if those slip, piece (1) has
  nothing to branch `[read]` PULSE-MAP.md:206. *Mitigation:* sequence piece (1) behind the
  relevant lanes (C/D/E/F); Phase 3 (format) and Phase 4 (submit) can land per-engine as each
  handler settles.
- **R4 — No DPC runtime to prove against (P3).** Without a Livy+Hive+Parquet environment the
  anchor can't run, so the path stays unproven to ADR-0009 `[read]` PULSE-MAP.md:228.
  *Mitigation:* extend the existing local MinIO harness rather than build fresh
  `[read]` CanonicalLoanMasterAirflowRuntimeIT.java:97.
- **R5 — Non-determinism breaking byte-exactness.** Parquet file-ordering, Hive metastore
  timestamps, or un-ORDER-BY'd dedup can make runs differ byte-wise. *Mitigation:* the ADR-0009
  method's explicit tiebreakers + audit-column normalization; the 2–3× re-run loop catches it
  `[read]` SPEC-codegen-compiler.md:331-335.
- **R6 — Config leakage (baked endpoints).** Livy host/`conn_id` baked into generated code
  violates §C.3. *Mitigation:* route through the `PULSE_ENV` → `config/<env>.yaml` slice; assert
  no literal endpoints in the anchor's emitted artifacts `[read]` SPEC-codegen-compiler.md:178-184.
- **R7 — Drift vs. the eventual DPC-Iceberg target.** Hive+Parquet is today's DPC format;
  Iceberg-on-DPC is a ~9–12mo roadmap item `[read]` PULSE-MAP.md:234. *Mitigation:* keep the
  format behind the Mode branch + format selector so the later Iceberg swap is localized, not a
  re-architecture.
