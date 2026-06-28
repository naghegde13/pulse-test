package com.pulse.storage;

import com.pulse.deploy.environment.DeploymentEnvironment;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.storage.model.ProvisioningStatus;
import com.pulse.storage.model.StorageAuthorityConflict;
import com.pulse.storage.model.StorageBackend;
import com.pulse.storage.repository.StorageAuthorityConflictRepository;
import com.pulse.storage.repository.StorageBackendRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Deploy-time gate that ensures every storage_backend a pipeline depends
 * on is {@code validated} for the target environment before the deploy
 * is allowed to proceed (#30 P10).
 *
 * <p>Rule (LOCKED, uniform across all envs):
 * <pre>
 *     For each SubPipelineInstance in the pipeline, look up
 *     storage_backends WHERE (tenant_id, environment, backend) matches the
 *     instance's storage_backend choice + the target deploy environment.
 *     The provisioning_status MUST be 'validated'.
 *     pending / failed / disabled / missing → deploy blocked with a clear
 *     error listing every offending row.
 * </pre>
 *
 * <p>Local-dev MinIO is handled by V96 seeding the seed-tenant rows as
 * {@code validated} from the start — no special-case in this gate.
 *
 * <p>The gate is a pure read-only check; it does NOT mutate any state. The
 * caller is responsible for proceeding (or not) based on the returned
 * {@link Result}.
 */
@Component
public class StorageBackendDeployGate {

    private final SubPipelineInstanceRepository instanceRepo;
    private final PipelineRepository pipelineRepo;
    private final StorageBackendRepository storageBackendRepo;
    private final StorageAuthorityConflictRepository conflictRepo;

    public StorageBackendDeployGate(SubPipelineInstanceRepository instanceRepo,
                                    PipelineRepository pipelineRepo,
                                    StorageBackendRepository storageBackendRepo,
                                    StorageAuthorityConflictRepository conflictRepo) {
        this.instanceRepo = instanceRepo;
        this.pipelineRepo = pipelineRepo;
        this.storageBackendRepo = storageBackendRepo;
        this.conflictRepo = conflictRepo;
    }

    /** Outcome of {@link #check(String, String)}. ok=true means deploy
     * can proceed; ok=false carries one or more {@link Blocker}s with
     * human-readable explanations. */
    public record Result(boolean ok, List<Blocker> blockers) {
        public Result {
            blockers = List.copyOf(blockers);
        }
        public String reason() {
            if (ok) return "ok";
            StringBuilder sb = new StringBuilder("Deploy blocked: ");
            for (int i = 0; i < blockers.size(); i++) {
                if (i > 0) sb.append("; ");
                sb.append(blockers.get(i).message());
            }
            return sb.toString();
        }
    }

    /** One reason a deploy is blocked. */
    public record Blocker(String backend, String environment, String reason, String message) {}

    /**
     * Check whether a pipeline can be deployed to the given environment.
     * Returns {@link Result#ok}=true when every storage_backend the
     * pipeline depends on is validated; otherwise returns false with
     * one Blocker per offending row.
     */
    public Result check(String pipelineId, String environment) {
        // Phase 1: normalize legacy uppercase env values (DEV, PROD, INT, ...)
        // to canonical lowercase keys so storage_backends row lookups always
        // hit the canonical (tenant, environment, backend) tuple.
        final String canonicalEnv;
        try {
            canonicalEnv = DeploymentEnvironment.normalize(environment);
        } catch (IllegalArgumentException badEnv) {
            return new Result(false, List.of(new Blocker(
                    null, environment, "unknown_environment", badEnv.getMessage())));
        }

        Pipeline pipeline = pipelineRepo.findById(pipelineId).orElse(null);
        if (pipeline == null) {
            return new Result(false, List.of(new Blocker(
                    null, canonicalEnv, "pipeline_not_found",
                    "Pipeline " + pipelineId + " not found.")));
        }
        String tenantId = pipeline.getTenantId();

        List<SubPipelineInstance> instances =
                instanceRepo.findByPipelineIdOrderByExecutionOrderAsc(pipelineId);
        if (instances.isEmpty()) {
            return new Result(false, List.of(new Blocker(
                    null, canonicalEnv, "empty_pipeline",
                    "Pipeline has no sub_pipeline_instances; nothing to deploy.")));
        }

        // Collect distinct backends actually used by this pipeline.
        Set<String> backends = new LinkedHashSet<>();
        for (SubPipelineInstance inst : instances) {
            String b = inst.getStorageBackend();
            if (b != null && !b.isBlank()) backends.add(b);
        }

        if (backends.isEmpty()) {
            return new Result(false, List.of(new Blocker(
                    null, canonicalEnv, "no_storage_backend",
                    "No SubPipelineInstance has storage_backend set; cannot determine target storage.")));
        }

        List<Blocker> blockers = new ArrayList<>();

        // ARCH-010: unresolved storage_authority_conflicts block deploy.
        // Backfill records these when legacy params overrides could not be
        // safely promoted to canonical columns; until they are resolved,
        // the pipeline's storage authority is ambiguous.
        List<StorageAuthorityConflict> openConflicts =
                conflictRepo.findByPipelineIdAndResolvedFalse(pipelineId);
        for (StorageAuthorityConflict c : openConflicts) {
            String detail = c.getDetail() != null && !c.getDetail().isBlank()
                    ? c.getDetail()
                    : "Unresolved storage authority conflict on instance " + c.getInstanceId();
            blockers.add(new Blocker(
                    c.getConflictingStorageBackend(),
                    canonicalEnv,
                    "storage_authority_conflict",
                    "Storage authority conflict on instance " + c.getInstanceId()
                            + " (reason=" + c.getReason() + "): " + detail
                            + ". Resolve via canonical instance update before deploy."));
        }

        for (String backend : backends) {
            Optional<StorageBackend> rowOpt = storageBackendRepo
                    .findByTenantIdAndEnvironmentAndBackend(tenantId, canonicalEnv, backend);
            if (rowOpt.isEmpty()) {
                blockers.add(new Blocker(backend, canonicalEnv, "missing_row",
                        "No storage_backends row for tenant=" + tenantId
                                + " env=" + canonicalEnv + " backend=" + backend
                                + ". Run tenant onboarding to create it."));
                continue;
            }
            StorageBackend row = rowOpt.get();
            ProvisioningStatus status = row.getProvisioningStatusEnum();
            if (status == ProvisioningStatus.VALIDATED) {
                continue;
            }
            String reason = status != null ? status.dbValue() : "unknown";
            String prefix = "Storage backend " + backend + " for "
                    + canonicalEnv + " (id=" + row.getId() + ") is " + reason + ".";
            String hint = switch (status == null ? ProvisioningStatus.PENDING : status) {
                case PENDING -> " Contact the platform team to provision the project, then run validation from the connector instance page.";
                case FAILED  -> " Last validation error: "
                        + (row.getProvisioningError() != null ? row.getProvisioningError()
                                                              : "(no message recorded)")
                        + ". Re-run validation once the underlying issue is resolved.";
                case DISABLED -> " This backend is disabled for this tenant. Choose a different storage_backend on the affected SubPipelineInstance(s) or re-enable the row.";
                default -> "";
            };
            blockers.add(new Blocker(backend, canonicalEnv, reason, prefix + hint));
        }

        return new Result(blockers.isEmpty(), blockers);
    }
}
