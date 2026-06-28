# G2 — Make PULSE's GCP Deploy Actually Call Real GCP — blind-implementer spec

> Status: **WRITTEN + PARKED (branch `g2-deploy-gcp`, `2f595a1`, unit-verified only) — NOT a fresh dispatch.** Evaluate per ADR 0008 + the integrated GCP-run scenario before merge (see PULSE-MAP "Branch & Merge State"). Method = ADR-0004 (behavioral test; the
> implementer must NOT see the oracle). When handing to an external coding agent
> (Codex/Droid), paste/point at **only** the *Self-contained context + Task +
> Constraints* sections — **never** the *Behavioral test* section.
>
> Scope: wire the **existing** tenant impersonation-credential resolver into the
> three deploy clients that currently throw. This is plumbing, **not** building
> new credential handling — the credential model, impersonation token-minting,
> tenant topology, and readiness checks already exist and are verified wired.

---

## Self-contained context (the implementer has no PULSE background)

PULSE deploys a "package" (a tree of generated Airflow DAG + PySpark + dbt files)
to a tenant's **Google Cloud Composer** (managed Airflow) environment so the DAG
runs on a schedule/trigger. The deploy is driven by a small state machine
(`DeploymentRunOrchestrator`) that calls a target adapter
(`GcpComposerDataprocAdapter`) which in turn calls three narrow "client" seams.
**Today all three production clients throw `UnsupportedOperationException`; their
in-process stubs do the work deterministically in tests/CI.** G2 = implement the
three production clients against real GCP, gated behind a flag.

Repo root: `/Users/aameradam/projects/dev/PULSE-guided-rehearsal`. All paths below
are under `backend/src/main/java/com/pulse/` unless noted. Every claim is
`file:line` + a short quote; **verify before editing** — flag uncertainty, do not
guess.

### The deploy flow (what runs, in order)

1. `POST /api/v1/packages/{packageId}/deploy` (`deploy/controller/DeployController.java:346`)
   builds a `DeploymentRun`, runs preflight, then — once `PREFLIGHT_PASSED` —
   drives the run forward: `orchestrator.runToTerminal(run.getId(), caller)`
   (`DeployController.java:492`) or `runThroughSubmit` when validation is async
   (`:489`). Deploy is **dev-only**: non-dev targets are rejected 403
   (`DeployController.java:370-372`: `if (!"dev".equals(canonicalEnv)) { throw ... DEV_ONLY_DEPLOY_MESSAGE; }`).

2. `DeploymentRunOrchestrator.runToTerminal` (`deploy/orchestrator/DeploymentRunOrchestrator.java:297`)
   runs `materialize → submit → poll` for the run's adapter, resolved by
   `targetType` (`:344-353`, `adapterForRun`). For a GCP target the adapter is
   `GcpComposerDataprocAdapter` (`deploy/adapter/gcp/GcpComposerDataprocAdapter.java:56`,
   `TARGET_TYPE = RuntimeCapabilityMatrix.GCP` at `:58`).

3. **materialize** (`GcpComposerDataprocAdapter.java:108-138`): first writes the
   package tree to local disk via the reused `LocalMaterializationAdapter`
   (`:111-112`), then **uploads it to GCS**:
   ```
   String prefix = "packages/" + deploymentRunId + "/";                 // :114
   String gcsUri = gcsClient.uploadPackagePrefix(
           new GcsPackageDeliveryClient.UploadRequest(
                   config.gcsBucket(), prefix,
                   local.sortedPaths(), config.tokenReference()));       // :115-120
   ```
   `local.sortedPaths()` is the sorted list of relative paths under the
   materialization output root, each prefixed `package/...`
   (`deploy/adapter/LocalMaterializationAdapter.java:286` `row.put("path", "package/" + normalized)`,
   returned as `sortedPaths` at `:343-345`). The on-disk root is
   `<materialization-root>/<runId>/` (`LocalMaterializationAdapter.java:180-181`;
   root defaults to `build/deployment-materialization`, `:127`). Files live under
   `<root>/<runId>/package/...`; the manifest is
   `<root>/<runId>/materialization-manifest.json` (`:329`). The resulting
   `gs://<bucket>/<prefix>` URI is stashed on the run as `gcsUri` (`:122-124`).

4. **submit** (`GcpComposerDataprocAdapter.java:152-199`): syncs the DAG into the
   Composer environment and optionally triggers a validation DAG run:
   ```
   ComposerDagSyncClient.SyncResult syncResult = composerClient.syncDags(
       new ComposerDagSyncClient.SyncRequest(
           config.composerEnvironment(), gcsUri, config.dagFilePaths(),
           validation.requested(), config.tokenReference(), validation.conf())); // :162-169
   ```
   The triggered run id (if any) is stashed as `validationDagRunId` (`:176`).

5. **poll** (`GcpComposerDataprocAdapter.java:201-243`): if a validation run was
   triggered, reads its state via
   `composerClient.pollDagRun(env, firstDagName(dagFilePaths), validationDagRunId, tokenReference)`
   (`:209-213`) and maps it to a `DeploymentRunState`. If no validation run, the
   adapter reports `SUCCEEDED`/`composer_dag_synced` on a clean sync (`:235-237`).
   The orchestrator's `poll` is idempotent — unchanged status writes no new
   evidence (`DeploymentRunOrchestrator.java:189-220`).

### CONFIRMED: Dataproc submission happens INSIDE the generated DAG, not here

The deploy client's real job is **(a) upload the package/DAG to the Composer DAGs
bucket in GCS** and **(b) sync + trigger + poll the DAG run on Composer** — it does
**NOT** submit Dataproc directly. Evidence:

- Adapter class javadoc: *"Execution-mode deploy flow publishes package bytes and
  syncs Airflow only. Optional smoke validation triggers an Airflow DAG run
  explicitly through Composer. **Dataproc submission belongs to generated DAG
  tasks at pipeline run time, not to this deploy adapter.**"*
  (`GcpComposerDataprocAdapter.java:50-53`).
- The adapter constructor injects **only** `GcsPackageDeliveryClient gcsClient`
  and `ComposerDagSyncClient composerClient` (`GcpComposerDataprocAdapter.java:80-81`).
  It does **not** inject `DataprocSubmitClient`, and never references it.
- Repo-wide, `DataprocSubmitClient` (and its `Default*`/`Stub*` impls) is
  referenced **only inside its own three files** — never injected into the
  adapter, orchestrator, controller, or any config/wiring. It is currently
  **dead/unwired**. (Verified: `grep -rln DataprocSubmitClient src/main/java`
  returns only `DataprocSubmitClient.java`, `DefaultDataprocSubmitClient.java`,
  `StubDataprocSubmitClient.java`.)
- G1's sibling spec confirms the generated DAG, in GCP mode, emits a
  `DataprocCreateBatchOperator` (Dataproc Serverless) for the actual Spark run
  (`docs/anchor/G1-BUILDER-SPEC.md:41-45`). So Dataproc is launched **by Airflow
  at DAG-run time**, authenticated by the Dataproc workload SA — not by this
  deploy client.

### The three throwing clients + their interfaces

All in `deploy/adapter/gcp/`. Each interface has a `Default*` (throws) and a
`Stub*` (deterministic, no network) impl, selected by `@ConditionalOnProperty`
(see "Bean wiring + toggle").

**(i) `GcsPackageDeliveryClient`** (`GcsPackageDeliveryClient.java:12`):
```java
String uploadPackagePrefix(UploadRequest request);                       // :19
record UploadRequest(String bucket, String prefix,
                     List<String> relativePaths, String tokenReference){} // :31-36
```
Contract: *"Upload the materialized package files to `gs://<bucket>/<prefix>/...`.
Returns the canonical `gs://` URI of the uploaded prefix"* (`:14-19`).
`Default` throws (`DefaultGcsPackageDeliveryClient.java:26-33`). `Stub` returns
`"gs://" + bucket + "/" + prefix` with no I/O (`StubGcsPackageDeliveryClient.java:36`).

**(ii) `ComposerDagSyncClient`** (`ComposerDagSyncClient.java:12`):
```java
SyncResult syncDags(SyncRequest request);                                // :20
DagRunStatus pollDagRun(String composerEnvironment, String dagId,
                        String dagRunId, String tokenReference);         // :25-26
default void cancelDagRun(...);                                          // :28-31
record SyncRequest(String composerEnvironment, String gcsPackagePrefix,
        List<String> dagFilePaths, boolean triggerImmediately,
        String tokenReference, Map<String,Object> validationConf){}      // :46-53
record SyncResult(List<String> syncedDagNames, String triggeredDagRunId){} // :61-64
record DagRunStatus(String dagRunId, DeploymentRunState effectiveState,
        String providerStatus, String failureReason){}                  // :66-71
```
`composerEnvironment` is `projects/<project>/locations/<region>/environments/<env>`
(`:37`). `gcsPackagePrefix` is `gs://<bucket>/<prefix>` (`:38`). `dagFilePaths`
are relative paths inside the package prefix (`:39`). `Default` throws and
documents the target: *"Implement against the Cloud Composer REST API
(https://composer.googleapis.com/v1)"* (`DefaultComposerDagSyncClient.java:18-23`).
`Stub` derives DAG names from file paths and returns a synthetic run id
(`StubComposerDagSyncClient.java:21-49`); its `pollDagRun` returns
`SUCCEEDED/"success"` (`:47-48`).

**(iii) `DataprocSubmitClient`** (`DataprocSubmitClient.java:11`):
```java
String submitPySparkJob(SubmitRequest request);                          // :18
JobStatus pollJob(String project, String region, String jobId);          // :25
record SubmitRequest(String project, String region, String cluster,
        String mainPyFile, List<String> pythonFiles, List<String> jarFiles,
        List<String> args, String tokenReference){}                      // :41-50
record JobStatus(String jobId, DeploymentRunState effectiveState,
        String providerStatus){}                                         // :57-61
```
`Default` throws (`DefaultDataprocSubmitClient.java:17-30`). **As established
above this client is unwired** — nothing calls it.

### The credential resolver to wire in (already exists, already mints tokens)

`auth/service/TenantGcpCredentialResolver.java` returns a ready-to-use Google
`Credentials` for a tenant:
```java
public Credentials resolveCredentials(String tenantId) throws IOException   // :182
```
IMPERSONATION mode (recommended) loads ADC and wraps it:
```java
GoogleCredentials source = GoogleCredentials.getApplicationDefault();        // :191
return ImpersonatedCredentials.create(source,
        cred.getTenantServiceAccountEmail(), null,
        DEFAULT_SCOPES, IMPERSONATION_LIFETIME_SECONDS);                      // :195-200
```
Scope is `https://www.googleapis.com/auth/cloud-platform` (`:51-52`); token
lifetime 900s (`:54-55`). **No SA key material is stored or returned for
IMPERSONATION** — the tenant supplies only the SA email; ADC is the source
identity (`:33-37`). On local-dev ADC absence it throws a specific actionable
message: `"ADC not available — run 'gcloud auth application-default login' first"`
(`:58-59`, `:192-193`). STATIC_KEY mode (legacy/deprecated, `:28-32`) returns
`ServiceAccountCredentials.fromStream(...)` (`:208-209`).

**Reuse pattern already in the codebase** — copy it. `storage/service/GcsStorageClientFactory.java`
builds a tenant-scoped `Storage` client from the resolver in exactly the shape G2
needs:
```java
public Storage build(String tenantId, String gcpProjectId) throws IOException { // :41
    Credentials credentials = credentialResolver.resolveCredentials(tenantId);  // :44
    return StorageOptions.newBuilder()
            .setProjectId(gcpProjectId).setCredentials(credentials)
            .build().getService();                                              // :45-49
}
```

### The tenant runtime topology (project ids, Composer env + bucket, regions, SA emails)

`tenant/model/TenantGcpRuntimeTopology.java` (one row per tenant, unique on
`tenant_id`, `:26-27`) carries the live targets:
- Composer: `composerProjectId`, `composerEnvironment`, `composerRegion`,
  **`composerEnvironmentBucket`** (the Composer-managed DAGs bucket),
  **`composerDagPrefix`** (the `dags/` folder prefix) (`:30-37`, getters `:94-116`).
- Dataproc: `dataprocProjectId`, `dataprocRegion`, `dataprocWorkloadSaEmail`,
  `dataprocStagingBucket` (`:40-45`).
- Iceberg/lake: `icebergStorageBucket` (`:60`); BigQuery datasets bronze/silver/gold
  (`:50-52`).
- Control-plane SA: `controlPlaneSaEmail` (`:87`) — the SA the deploy/control-plane
  impersonates for Composer + GCS.

Fetch by tenant via `tenant/repository/TenantGcpRuntimeTopologyRepository.java`:
`Optional<TenantGcpRuntimeTopology> findByTenantId(String tenantId)`.
Precedent for resolving topology-by-tenant in the deploy package:
`deploy/boundary/DeployBoundaryService.java:69`
(`topologyRepo.findByTenantId(tenantId)`), which already derives Composer project/
region/bucket from this record (`:88-93`, `:110-120`).

### Bean wiring + the toggle (where `Default*` vs `Stub*` is chosen)

There is **no `@Bean` factory** for these clients — selection is purely
`@ConditionalOnProperty` on the `@Component` impls:
- `Default*` (production): `@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled", havingValue = "true")`
  (`DefaultGcsPackageDeliveryClient.java:22`, `DefaultComposerDagSyncClient.java:13`,
  `DefaultDataprocSubmitClient.java:13`).
- `Stub*` (default): `@ConditionalOnProperty(value = "pulse.deploy.runtime.gcp.enabled", havingValue = "false", matchIfMissing = true)`
  (`StubGcsPackageDeliveryClient.java:21-22`, `StubComposerDagSyncClient.java:16-17`,
  `StubDataprocSubmitClient.java:14-15`).

So flipping `pulse.deploy.runtime.gcp.enabled=true` swaps all three stubs for the
`Default*` impls and nothing else changes. **The flag is currently NOT present in
any `application*.yml`** (verified: no match in `src/main/resources`) → default
behavior is stub (`matchIfMissing=true`). CI never enables it.

### Dependencies already on the classpath

`backend/build.gradle.kts`: `com.google.cloud:google-cloud-storage:2.55.0`
(`:66`), `com.google.cloud:google-cloud-secretmanager:2.55.0` (`:60`). The
google-auth library (`ImpersonatedCredentials`, `GoogleCredentials`) is already
used by the resolver. **`google-cloud-dataproc` is NOT present, and there is no
Composer REST/SDK dependency** — see Risks.

### The package shape (what gets produced, what gets uploaded)

`LocalMaterializationAdapter` writes, under `<root>/<runId>/`:
- `package/<normalized-path>` — every generated artifact, byte-for-byte
  (`LocalMaterializationAdapter.java:279-281`). DAG files live here, e.g.
  `package/dags/pipeline_dag.py` (default `dagFilePaths` in
  `GcpComposerDataprocAdapter.java:378-379`).
- `materialization-manifest.json` — deterministic manifest of every file + SHA-256
  (`:329-332`).
- `evidence-index.json` — evidence rows (`:340-341`).

The materialize leg uploads the package tree under
`gs://<bucket>/packages/<runId>/` via `local.sortedPaths()` (each a `package/...`
relative path) — that's what `uploadPackagePrefix` must push.

---

## Task

Implement the three production clients so that, with
`pulse.deploy.runtime.gcp.enabled=true`, a dev-only deploy of a real package
performs real GCS + Composer operations against the tenant's topology, using
impersonated credentials from `TenantGcpCredentialResolver`. **Do not invent a new
credential mechanism** — wire the existing one.

### Prerequisite plumbing (the real wiring gap — read carefully)

The client request records carry only a `tokenReference` string (a `gcp-sm://`
URI from `GcpTargetConfig`, `GcpComposerDataprocAdapter.java:382-383`), **not a
`tenantId`**, and the `Default*` clients are plain `@Component`s with no
resolver/topology injected. `TenantGcpCredentialResolver.resolveCredentials` and
`TenantGcpRuntimeTopologyRepository.findByTenantId` are **keyed by `tenantId`**.
So the implementer MUST get the tenant identity (and the real Composer
bucket/env) to the clients. Choose the lowest-risk path and keep the seam shape:

1. **Inject** `TenantGcpCredentialResolver` and `TenantGcpRuntimeTopologyRepository`
   (or a thin factory like `GcsStorageClientFactory`) into the three `Default*`
   clients via constructor.
2. **Carry `tenantId` into the client calls.** The adapter has it in scope at every
   call site (`run.getTenantId()` is read at `GcpComposerDataprocAdapter.java:341`;
   `loadRun` is called in both `materialize` and `submit`). Add a `tenantId` field
   to `UploadRequest` and `SyncRequest` (and pass it to `pollDagRun`), populated
   from `run.getTenantId()` in the adapter. Update the `Stub*` impls to accept the
   new field (they can ignore it) so the contract tests still compile/pass.
   - **Resolve real targets from topology, not from `GcpTargetConfig` defaults.**
     `GcpTargetConfig.from` invents fixture defaults (project `pulse-dev`, bucket
     `<project>-pulse-packages`, env `.../environments/pulse-composer`,
     `GcpComposerDataprocAdapter.java:371-385`) that are wrong for live GCP. For the
     live path, the bucket to upload DAGs into must be the Composer-managed
     **`composerEnvironmentBucket` + `composerDagPrefix`** from the tenant topology,
     and the Composer environment name must be the topology's
     `composerProjectId`/`composerRegion`/`composerEnvironment`. Prefer topology
     values; fall back to target config only when topology is absent (mirror
     `DeployBoundaryService` precedence, `:88-107`).

### (i) `DefaultGcsPackageDeliveryClient` — real GCS upload

Upload each file listed in `UploadRequest.relativePaths()` from the local
materialization root (`<materialization-root>/<runId>/...`; the paths are already
`package/...`-prefixed) to `gs://<bucket>/<prefix><relativePath>`, then return the
canonical `gs://<bucket>/<prefix>` URI (the existing return contract,
`GcsPackageDeliveryClient.java:14-19`; stub returns the same shape,
`StubGcsPackageDeliveryClient.java:36`).
- Build the `Storage` client exactly like `GcsStorageClientFactory.build(tenantId, projectId)`
  (`GcsStorageClientFactory.java:41-49`) — `resolveCredentials(tenantId)` →
  `StorageOptions.setProjectId(...).setCredentials(...)`. Project id = the bucket's
  project (Composer project from topology).
- Read each file's bytes from disk under the materialization root and
  `storage.create(BlobInfo.newBuilder(bucket, prefix + relativePath).build(), bytes)`.
  Upload is idempotent by object name (re-deploy overwrites). Keep determinism:
  iterate `relativePaths` in the given (already-sorted) order.
- **Target the Composer DAGs bucket** for DAG files. Simplest correct behavior:
  upload the whole package tree under `gs://<composerEnvironmentBucket>/<prefix>`
  AND ensure the DAG files land under the Composer `dags/` prefix
  (`composerDagPrefix`) so Airflow picks them up — see (ii) for the split.

### (ii) `DefaultComposerDagSyncClient` — push DAG, trigger run, poll terminal state

- **Sync**: copy the DAG file(s) (`SyncRequest.dagFilePaths()`, relative paths
  inside the package prefix) into the Composer environment's DAGs folder —
  `gs://<composerEnvironmentBucket>/<composerDagPrefix>/<dagFileName>`. Composer
  loads DAGs from that GCS folder, so a GCS object write **is** the sync. (You may
  copy GCS→GCS from the package prefix, or upload from disk — either is fine; use
  the tenant `Storage` client from the resolver.) Return `SyncResult` with the
  derived DAG names (derive like the stub: basename minus `.py`,
  `StubComposerDagSyncClient.java:59-66`).
- **Trigger** (only when `SyncRequest.triggerImmediately()` is true): start an
  Airflow DAG run with `validationConf` as the run conf, and return its run id as
  `SyncResult.triggeredDagRunId()`. Use the **Airflow REST API of the Composer
  environment** (the Composer environment exposes an Airflow web server;
  authenticate with the impersonated credential's bearer token). Document the exact
  endpoint you use. After a fresh DAG-file sync, Airflow needs a short interval to
  parse the new DAG before it can be triggered — implement a bounded
  parse-wait/retry, not an unbounded loop.
- **Poll** (`pollDagRun`): read the Airflow DAG-run state for the triggered run and
  map it to a `DeploymentRunState` in `DagRunStatus` (the adapter maps `SUCCEEDED →
  "SUCCEEDED"`, etc., `GcpComposerDataprocAdapter.java:477-487`; the orchestrator
  drives the run forward on a changed status, `DeploymentRunOrchestrator.java:212-218`).
  Map Airflow `success → SUCCEEDED`, `failed → FAILED`, `running/queued → RUNNING`.
  Populate `failureReason` on failure.
- **Composer specifics**: resolve the Airflow web-server URL and the DAGs bucket
  from the Composer environment. The honest way is one Composer Admin REST call
  (`GET https://composer.googleapis.com/v1/{environment}`) to read
  `config.airflowUri` and `config.dagGcsPrefix`; cache per-environment. Use the
  same impersonated `Credentials` (refresh to a bearer token) for both the Composer
  Admin call and the Airflow REST call. Document whichever mechanism you choose.

### (iii) `DefaultDataprocSubmitClient` — confirm no-op, implement accordingly

**Confirmed it is NOT on the live deploy path** (see Self-contained context:
nothing injects or calls it; Dataproc runs inside the generated DAG via
`DataprocCreateBatchOperator`). Do **one** of:
- **Preferred:** leave it as an explicit, documented no-op/unsupported — replace the
  current `UnsupportedOperationException` with a clear comment stating the deploy
  client does not submit Dataproc (the DAG does), so an accidental future caller
  gets a precise message. Do not add a `google-cloud-dataproc` dependency for a
  path nothing exercises. **Do not delete the class** (it is part of the gated
  triple and the `Stub*` is still selected when the flag is off).
- If you believe a real submit is needed, **stop and flag it** — that contradicts
  the architecture and the behavioral test, and would require new IAM + a new
  dependency. Default to the no-op.

---

## Constraints

- **Impersonation only — no SA keys.** Obtain every credential via
  `TenantGcpCredentialResolver.resolveCredentials(tenantId)` (IMPERSONATION mode
  mints `ImpersonatedCredentials` from ADC, `TenantGcpCredentialResolver.java:188-200`).
  Never read, write, log, or embed SA key JSON. Do not call `resolveCredentialJson`
  (it hard-fails for impersonated tenants, `:231-235`).
- **Tenant-scoped via `TenantGcpRuntimeTopology`.** Resolve project ids, Composer
  environment/region, the Composer DAGs bucket (`composerEnvironmentBucket` +
  `composerDagPrefix`), and SA emails from the tenant's topology row
  (`TenantGcpRuntimeTopologyRepository.findByTenantId`), preferring topology over
  the `GcpTargetConfig` fixture defaults. No hardcoded project/bucket/principal.
- **Do NOT break the DPC path.** The DPC adapter
  (`deploy/adapter/dpc/DpcAirflowOpenShiftSparkAdapter.java`) is a separate
  `DeploymentTargetAdapter` and must not regress. Don't touch shared
  orchestrator/state-machine semantics; only the three GCP clients + the request
  records they own + the adapter call sites that populate `tenantId`.
- **Gate behind `pulse.deploy.runtime.gcp.enabled`.** Keep the `@ConditionalOnProperty`
  split intact: `Default*` only when `true`, `Stub*` when `false`/missing. With the
  flag off (CI default) nothing changes and the stub contract tests must still pass.
  Update the `Stub*` impls only as needed to accept the new request fields.
- **Pass the forbidden-token/secret scans.** Generated/committed code must contain
  no raw tokens, PATs, private keys, passwords, or SA JSON. Secrets are
  **SecretRefs only** (`gcp-sm://...` references) — the `tokenReference` field is a
  reference, never a value; do not resolve it into a logged/persisted secret. (See
  the forbidden-token / secret-scan expectations in
  `docs/verification/PULSE_IMPLEMENTATION_PACKETS.md` and ADR-0002,
  `docs/adr/0002-llm-grounded-builder.md:11`.)
- **Dev-only.** The deploy endpoint already rejects non-dev targets
  (`DeployController.java:370-372`); do not add any path that writes to higher envs.
- **Bounded, observable failures.** Network/auth failures must surface as
  `RuntimeException`s the adapter already catches into a `FAILED` execution
  (`GcpComposerDataprocAdapter.java:132-137`, `:193-198`); do not swallow them.

---

## Behavioral test (evaluator-only — DO NOT include when dispatching the implementer)

Given a **built package for the `loan_master` pipeline** (the anchor pipeline)
materialized to disk, deployed against tenant topology pointing at GCP project
**`wf-pulse-agentic-dev2`** (control-plane SA `sa_pulse_cgdev2`, Dataproc
Serverless, a freshly-created Composer environment), with
`pulse.deploy.runtime.gcp.enabled=true`:

1. The **real** `DefaultGcsPackageDeliveryClient` uploads the package tree — including
   the generated DAG — into the tenant's **Composer-managed DAGs bucket** under the
   run prefix, and the DAG file is present at
   `gs://<composerEnvironmentBucket>/<composerDagPrefix>/<dag>.py`.
2. The deploy **triggers a DAG run** on that Composer environment and `pollDagRun`
   observes it reach **`success`** (terminal `SUCCEEDED`), driving the
   `DeploymentRun` to `SUCCEEDED`.
3. As a result of that DAG run, the **bronze table lands in the GCS lake** (the
   `loan_master` bronze output appears under the tenant's lake bucket, per the
   active GCP table contract / ADR-0007 interim Iceberg-on-GCS).

Checked by an **independent evaluator** against real GCP (`gsutil`/`bq`/Airflow API
readbacks), not by the implementer. The implementer never sees this section.
(Project/SA/Composer coordinates: `docs/PULSE-MAP.md:97`, `:99`, `:146`.)
