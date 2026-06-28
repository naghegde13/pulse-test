# Doc-Hygiene Audit — REVIEW LIST (2026-06-15)

> **Status: `[report]` — for operator review. NOTHING moved/edited/deleted** (ADR 0005:
> never hard-delete unreviewed). Transient working artifact — delete after the cleanup is
> executed. Staleness judged from file content + headers/dates + the canonical docs
> (PULSE-MAP / CONTEXT / ADRs); git last-touched dates were NOT used (Bash was sandbox-blocked
> for the auditing agent) — request a git-dated cross-check before deleting anything you're unsure of.

## Counts
283 `.md` total → 19 are build copies under `backend/build/...` (ignore/`.gitignore`) → **264 real docs**:
**KEEP 35 · KEEP-BUT-STALE 49 · QUARANTINE 145 · DELETE-CANDIDATE 35.**

The bulk of QUARANTINE is the two predicted buckets: `docs/architecture/` (~26 dated plans) and
`docs/verification/` (~60 proof/transcript artifacts), plus the root packet reports.

## QUARANTINE → move to `docs/_archive/` + banner (per ADR 0005). Operator action list.
**Root (point-in-time packet/audit/handoff):** `CLAUDE_PACKET_REPORT.md` + all 16
`CLAUDE_PACKET_REPORT_PKT_*.md`; `AGENTS.md` (superseded by PULSE-MAP+CONTEXT); `DEVIATIONS.md`;
`GenAI_Pipeline_Builder_Consolidated_Build_Pack_FULL_v1.md` (pre-rescue); `frontend-audit-report.md`;
`backend/BACKEND_AUDIT_REPORT.md`.
**docs/ root:** `MORNING_REPORT.md`, `PARALLEL_WORKSTREAM_CONTRACTS.md`, `pulse3-deployment-plan.md`
(wrong project), `PIPELINE_BENCHMARK_SUITE_GCP_API_COMMAND_2026-05-14.md`,
`backend-non-e2e-regression-profile-2026-04-26.md`, `backend-regression-sharding-plan-2026-04-26.md`,
`memory/project_redesign_audit.md`. (`AUTONOMOUS_RUN_PREFLIGHT.md` borderline — KEEP-BUT-STALE if the
night-run workflow continues; operator's call.)
**docs/blueprints/:** `BLUEPRINT-SESSION-KICKOFF.md`; `SESSION-A-MERGE-HANDOFF.md` (PULSE-MAP cites it
as "full detail" — operator may prefer KEEP-BUT-STALE).
**docs/architecture/ (~26):** the ARCH-015–018 + dated `*-architecture-plan-*`/`*-implementation-plan-*`
pairs (partitioning, developer-git-workspace, advance-time-dimension ×4, airflow-callback ×3,
plan-draft-ref-aliasing ×2, system-prompt-chat-tool-rewrite ×2, tenant-settings-runtime-persona ×2,
deploy-runtime-orchestration-boundary ×2), `codex-implementation-prompt-2026-05-13.md`,
`agentic-e2e-scenario-family-expansion-handoff-2026-04-26.md`, `e2e-architecture-rehearsal-todo-2026-05-12.md`,
`external-agent-prompts/*` (3), `claude-redesign-plan*/squishy-questing-badger*.md`.
**docs/verification/ (~60):** all `artifacts/acme-lending-*` (25), `artifacts/SU-*-DEVIATIONS.md` (9),
`INTEGRATOR-DEVIATIONS.md`, all 11 `artifacts/PKT-CAND-2026-05-26-*.md`, the PKT-FINAL summaries/plans/
evidence notes, and dated root verification docs (`backend-e2e-nonlive-regression-profile-2026-04-26.md`,
`blueprint-semantic-hardening-gcp-runtime-proof-plan.md`, `gcp-composer-dataproc-bridge-real-build-instructions.md`,
`json-struct-dedupe-runtime-evidence-audit-2026-04-26.md`, `priority-proof-oracle-semantic-validation-2026-04-27.md`,
`remaining-runtime-blocker-audit-2026-04-26.md`, `e2e-assurance-plan-2026-05-14.md`,
`end-to-end-gap-discovery-playbook-2026-05-14.md`, `runtime-harness-scope-and-instrumentation-2026-04-26.md`,
`PULSE_CAPABILITY_COVERAGE_PROOF.md`, `PULSE_IMPLEMENTATION_PACKETS.md`).
**docs/plans/ + docs/reviews/:** `e2e-assurance-automation-2026-05-15.md`,
`e2e-assurance-plan-critique-2026-05-15.md`.

## DELETE-CANDIDATE → operator reviews before ANY deletion (ADR 0005 leans archive over delete)
**docs/architecture/ redesign duplicates (16):** `temp.md` (3,315-line scratch dup of squishy-questing-badger_v2);
`claude-redesign-plan/squishy-questing-badger_v2.md` (dup of the v2-dir copy);
`claude-redesign-plan/agent-briefs/*` (6, v1 superseded by v2); `claude-redesign-plan-v2/agent-briefs/*`
(8 — redesign plan itself superseded by PULSE-MAP). *Confirm v1 vs v2 aren't byte-identical first.*
**docs/verification/ stubs (2):** `gcp-target-topology-runtime-proof-2026-04-26/gcp-deployment-productization-plan.md`,
`.../gcp-readiness-intake-and-setup.md` (2026-04 GCP stubs, superseded by actual GCP work).
**Versioned predecessors (operator discretion — QUARANTINE if you want the full chain):**
acme-lending guided-chat-transcript v1/v2/v3 (→v4), scenario-narrative-matrix v1–v4 (→v5),
SESSION4-STARTUP-PROMPT (→SESSION5).
**NOT docs — code check first:** `backend/src/main/resources/cobol-discovery/patterns_orig.md` +
`patterns_v3.md` (versioned siblings of the active `patterns.md`; `patterns_v3` may be NEWER — confirm
which the EBCDIC engine loads before touching).

## KEEP-BUT-STALE → update in place (top-priority: cited as canonical but contradicted by current ADRs)
- `docs/SCHEMA_DISCOVERY_ANALYSIS.md` — pushes "hybrid static + AI schema," **reversed by ADR 0011 /
  CONTEXT.md** (schema inference is now 100% deterministic). Misleading if read as current.
- `docs/PIPELINE_BENCHMARK_SUITE.md` — predates the anchor pipeline + behavioral-test method (ADR 0004).
- `docs/PERIODICITY_ANALYSIS.md` — partly codified in ADRs 0021/0022/0023; reconcile.
- `docs/AIRFLOW_INTEGRATION_DECISIONS.md` — predates ADRs 0006/0021/0022.
- `docs/USER_JOURNEYS.md` — references the removed Playwright suite.
- `docs/AI_REQUIREMENTS_GATHERING_ANALYSIS.md` — predates the Lane-C chat rework.
- `docs/ARCHITECTURE_LEDGER.md` — predecessor of the ADR system; should point to `docs/adr/`.
- `docs/blueprints/BLUEPRINT-RATIONALIZATION.md` — DRAFT, now superseded-by ADRs 0020–0023.
- + ~40 more (UI_UX_NORTH_STAR_PLAN, recommended_tests, RUNTIME_E2E_FINDINGS, GX_INTEGRATION_PROPOSAL,
  EBCDIC_DISCOVERY_BRIEF, the `docs/testing/*` set, the ARCH-009–014 plan pairs, PULSE_SUITE_ALIGNMENT_MAP,
  PULSE_E2E_GAP_REGISTER, param-overlap-sor-vs-blueprints) — dated reference docs, verify against current.

## CLAUDE.md — concrete factual fixes (CLAUDE.md itself is KEEP-BUT-STALE; update in place)
1. **~Line 11/migrations:** "Flyway migrations (V1–V80)" → real head is **V152** (g1 branch).
2. **~Line 135 seeded data:** "Two tenants (acme, globex)… 35 blueprints" → tenants are
   **home-lending / unsecured-lending** (no acme/globex, per V87); blueprint count is **43 non-deprecated**.
3. **~Line 138 Key Design Decisions:** points to `SCHEMA_DISCOVERY_ANALYSIS.md` for "Hybrid static + AI" →
   **contradicts ADR 0011 / CONTEXT.md** (deterministic, AI removed). Point the block at `docs/adr/` + PULSE-MAP.
4. Broader Domain-Concepts section is pre-rescue framing (superseded by CONTEXT.md + ADRs) — full reconcile
   warranted, but the three above are the concrete errors.
