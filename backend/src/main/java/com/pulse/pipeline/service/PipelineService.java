package com.pulse.pipeline.service;

import com.pulse.auth.service.TenantService;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.service.CommandService;
import com.pulse.command.service.PlanService;
import com.pulse.command.service.PlanService.PlannedCommand;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.controller.PipelineController.CreatePipelineRequest;
import com.pulse.pipeline.controller.PipelineController.CreateRevisionRequest;
import com.pulse.pipeline.controller.PipelineController.UpdateOrchestrationRequest;
import com.pulse.pipeline.controller.PipelineController.UpdatePipelineRequest;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.SchemaConflictRepository;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository versionRepository;
    private final DomainRepository domainRepository;
    private final TenantService tenantService;
    private final CommandService commandService;
    private final PlanService planService;
    private final SchemaConflictRepository schemaConflictRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    private static final String ACTOR_STUB = "stub-user-001";

    public PipelineService(PipelineRepository pipelineRepository,
                           PipelineVersionRepository versionRepository,
                           DomainRepository domainRepository,
                           TenantService tenantService,
                           CommandService commandService,
                           PlanService planService,
                           SchemaConflictRepository schemaConflictRepository,
                           RuntimeAuthorityService runtimeAuthorityService) {
        this.pipelineRepository = pipelineRepository;
        this.versionRepository = versionRepository;
        this.domainRepository = domainRepository;
        this.tenantService = tenantService;
        this.commandService = commandService;
        this.planService = planService;
        this.schemaConflictRepository = schemaConflictRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    public List<Pipeline> listByTenant(String tenantId) {
        tenantService.getTenant(tenantId);
        return pipelineRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
    }

    public List<Pipeline> listByDomain(String tenantId, String domainName) {
        tenantService.getTenant(tenantId);
        return pipelineRepository.findByTenantIdAndDomainNameOrderByUpdatedAtDesc(tenantId, domainName);
    }

    public List<Pipeline> listByDomainId(String tenantId, String domainId) {
        tenantService.getTenant(tenantId);
        return pipelineRepository.findByTenantIdAndDomainIdOrderByUpdatedAtDesc(tenantId, domainId);
    }

    public Pipeline get(String tenantId, String pipelineId) {
        tenantService.getTenant(tenantId);
        Pipeline pipeline = pipelineRepository.findById(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId));
        if (!pipeline.getTenantId().equals(tenantId)) {
            throw new ResourceNotFoundException("Pipeline", pipelineId);
        }
        return pipeline;
    }

    public PipelineVersion getVersion(String versionId) {
        return versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));
    }

    public List<PipelineVersion> listVersions(String pipelineId) {
        return versionRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
    }

    public Pipeline create(String tenantId, CreatePipelineRequest request) {
        tenantService.getTenant(tenantId);
        DomainResolution domain = resolveDomain(tenantId, request.domainId(), request.domainName());

        String defaultStorageBackend = resolveDefaultStorageBackendForCreate(request.defaultStorageBackend());

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("name", request.name());
        payload.put("description", request.description() != null ? request.description() : "");
        payload.put("domainName", domain.domainName());
        payload.put("domainId", domain.domainId());
        payload.put("defaultStorageBackend", defaultStorageBackend);

        // Single-command plan: preview + apply in one shot for creates
        var planned = new PlannedCommand(
                PipelineCommandHandlers.CREATE_PIPELINE, "Pipeline", "new",
                "Create pipeline: " + request.name(), payload);
        var plan = planService.createPreview(tenantId, null, ACTOR_STUB,
                "Create pipeline: " + request.name(), List.of(planned));
        planService.apply(plan.getId(), tenantId, ACTOR_STUB, List.of(planned));

        // Return the created pipeline
        return pipelineRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Pipeline creation command failed"));
    }

    public String resolveDefaultStorageBackendForCreate(String requestedStorageBackend) {
        String explicit = normalizeStorageBackend(requestedStorageBackend);
        if (explicit != null) {
            validateStorageBackendValue(explicit, requestedStorageBackend);
            return explicit;
        }

        RuntimeAuthority authority = runtimeAuthorityService.getAuthority();
        if (authority == null) {
            throw new IllegalStateException("Runtime authority is not initialized; cannot derive defaultStorageBackend");
        }

        boolean gcpAllowed = authority.allowedStorageBackends().contains("GCP");
        boolean dpcAllowed = authority.allowedStorageBackends().contains("DPC");
        if (gcpAllowed && !dpcAllowed) return "GCP";
        if (dpcAllowed && !gcpAllowed) return "DPC";

        RuntimePersona persona = authority.activePersona();
        return switch (persona) {
            case GCP_PULSE -> "GCP";
            case DPC_PULSE -> "DPC";
        };
    }

    private String normalizeStorageBackend(String storageBackend) {
        if (storageBackend == null || storageBackend.isBlank()) {
            return null;
        }
        return storageBackend.trim().toUpperCase(Locale.ROOT);
    }

    private void validateStorageBackendValue(String normalizedStorageBackend, String originalStorageBackend) {
        if (!"DPC".equals(normalizedStorageBackend) && !"GCP".equals(normalizedStorageBackend)) {
            throw new IllegalArgumentException(
                    "Invalid defaultStorageBackend '" + originalStorageBackend + "'. Must be 'DPC' or 'GCP'.");
        }
    }

    @Transactional
    public Pipeline update(String tenantId, String pipelineId, UpdatePipelineRequest request) {
        get(tenantId, pipelineId);

        Map<String, Object> payload = new LinkedHashMap<>();
        if (request.name() != null) payload.put("name", request.name());
        if (request.description() != null) payload.put("description", request.description());

        // ARCH-010 update semantics: null/omitted preserves the existing value;
        // explicit blank is rejected; only 'DPC' or 'GCP' is accepted. Changing
        // this never rebases existing instances; new instances pick it up.
        if (request.defaultStorageBackend() != null) {
            String v = request.defaultStorageBackend();
            if (v.isBlank()) {
                throw new IllegalArgumentException(
                        "defaultStorageBackend cannot be blank; omit the field to preserve, or pass 'DPC'/'GCP'.");
            }
            if (!"DPC".equals(v) && !"GCP".equals(v)) {
                throw new IllegalArgumentException(
                        "Invalid defaultStorageBackend '" + v + "'. Must be 'DPC' or 'GCP'.");
            }
            payload.put("defaultStorageBackend", v);
        }

        commandService.execute(PipelineCommandHandlers.UPDATE_PIPELINE,
                "Pipeline", pipelineId, tenantId, ACTOR_STUB, null, payload);

        return get(tenantId, pipelineId);
    }

    @Transactional
    public PipelineVersion transitionStage(String tenantId, String pipelineId,
                                           String versionId, PipelineStage targetStage) {
        get(tenantId, pipelineId);
        PipelineVersion version = getVersion(versionId);
        if (!version.getPipelineId().equals(pipelineId)) {
            throw new IllegalArgumentException("Version does not belong to this pipeline");
        }
        if (targetStage != PipelineStage.ENGINEERING) {
            int openConflicts = schemaConflictRepository
                    .findByVersionIdAndResolutionStatusOrderByCreatedAtDesc(versionId, "open")
                    .size();
            if (openConflicts > 0) {
                throw new IllegalArgumentException(
                        "Cannot transition with " + openConflicts + " open schema conflict"
                                + (openConflicts == 1 ? "" : "s") + ".");
            }
        }

        Map<String, Object> payload = Map.of(
                "versionId", versionId,
                "targetStage", targetStage.name());

        CommandLog cmd = commandService.execute(PipelineCommandHandlers.TRANSITION_STAGE,
                "PipelineVersion", versionId, tenantId, ACTOR_STUB, null, payload);

        if (cmd.getStatus() == CommandStatus.FAILED) {
            throw new IllegalArgumentException(cmd.getErrorMessage());
        }

        return getVersion(versionId);
    }

    @Transactional
    public PipelineVersion createNewRevision(String tenantId, String pipelineId,
                                             CreateRevisionRequest request) {
        get(tenantId, pipelineId);

        Map<String, Object> payload = Map.of(
                "changeSummary", request.changeSummary() != null ? request.changeSummary() : "");

        CommandLog cmd = commandService.execute(PipelineCommandHandlers.CREATE_REVISION,
                "Pipeline", pipelineId, tenantId, ACTOR_STUB, null, payload);

        if (cmd.getStatus() == CommandStatus.FAILED) {
            throw new IllegalArgumentException(cmd.getErrorMessage());
        }

        return versionRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalStateException("Revision creation failed"));
    }

    @Transactional
    public PipelineVersion updateOrchestration(String tenantId,
                                               String pipelineId,
                                               String versionId,
                                               UpdateOrchestrationRequest request) {
        get(tenantId, pipelineId);
        PipelineVersion version = getVersion(versionId);
        if (!version.getPipelineId().equals(pipelineId)) {
            throw new IllegalArgumentException("Version does not belong to this pipeline");
        }

        version.setScheduleCron(request.scheduleCron());
        version.setCatchupEnabled(request.catchupEnabled());
        version.setMaxActiveRuns(request.maxActiveRuns());
        version.setDependsOnPast(request.dependsOnPast());

        Map<String, Object> metadata = new LinkedHashMap<>(
                version.getMetadata() != null ? version.getMetadata() : Map.of()
        );
        Map<String, Object> schedulePolicyConfig = extractNestedPolicyConfig(
                request.policyConfigs(), "ScheduleAndTriggers");
        String scheduleType = extractString(schedulePolicyConfig.get("schedule_type"),
                request.scheduleCron() != null ? "cron" : "manual");

        Map<String, Object> orchestrationPolicy = new LinkedHashMap<>();
        orchestrationPolicy.put("scheduleType", scheduleType);
        orchestrationPolicy.put("scheduleCron",
                "cron".equals(scheduleType)
                        ? request.scheduleCron() != null ? request.scheduleCron() : "@daily"
                        : null);
        if ("event".equals(scheduleType)) {
            orchestrationPolicy.put("triggerDataset",
                    extractString(schedulePolicyConfig.get("trigger_dataset"), ""));
        }
        orchestrationPolicy.put("timezone",
                extractString(schedulePolicyConfig.get("timezone"), "UTC"));
        orchestrationPolicy.put("retryCount",
                extractInteger(schedulePolicyConfig.get("retry_count"), 3));
        orchestrationPolicy.put("catchupEnabled", Boolean.TRUE.equals(request.catchupEnabled()));
        orchestrationPolicy.put("maxActiveRuns",
                request.maxActiveRuns() != null ? request.maxActiveRuns() : 1);
        orchestrationPolicy.put("dependsOnPast", Boolean.TRUE.equals(request.dependsOnPast()));
        metadata.put("orchestrationPolicy", orchestrationPolicy);
        metadata.put("orchestrationPolicyBlueprints",
                request.policyConfigs() != null ? request.policyConfigs() : Map.of());
        version.setMetadata(metadata);

        return versionRepository.save(version);
    }

    public Map<String, Long> getStats(String tenantId) {
        tenantService.getTenant(tenantId);
        List<Pipeline> pipelines = pipelineRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId);
        Map<String, Long> stats = new LinkedHashMap<>();
        stats.put("total", (long) pipelines.size());

        Map<PipelineStage, Long> stageCounts = new LinkedHashMap<>();
        for (PipelineStage stage : PipelineStage.values()) {
            stageCounts.put(stage, 0L);
        }
        for (Pipeline p : pipelines) {
            if (p.getActiveVersionId() != null) {
                versionRepository.findById(p.getActiveVersionId())
                        .ifPresent(v -> stageCounts.merge(v.getLifecycleStage(), 1L, Long::sum));
            }
        }
        stageCounts.forEach((stage, count) -> stats.put(stage.name(), count));
        return stats;
    }

    @Transactional
    public void delete(String tenantId, String pipelineId) {
        get(tenantId, pipelineId);

        CommandLog cmd = commandService.execute(PipelineCommandHandlers.DELETE_PIPELINE,
                "Pipeline", pipelineId, tenantId, ACTOR_STUB, null, Map.of());

        if (cmd.getStatus() == CommandStatus.FAILED) {
            throw new IllegalArgumentException(cmd.getErrorMessage());
        }
    }

    private DomainResolution resolveDomain(String tenantId, String domainId, String domainName) {
        if (domainId != null && !domainId.isBlank()) {
            Domain domain = domainRepository.findById(domainId)
                    .filter(d -> tenantId.equals(d.getTenantId()))
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Domain id '" + domainId + "' was not found for tenant '" + tenantId + "'."));
            return new DomainResolution(domain.getId(), domain.getName());
        }

        if (domainName != null && !domainName.isBlank()) {
            Domain domain = domainRepository.findByTenantIdAndName(tenantId, domainName)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Domain '" + domainName + "' was not found for tenant '" + tenantId + "'."));
            return new DomainResolution(domain.getId(), domain.getName());
        }

        List<String> domains = tenantService.getDomainsForTenant(tenantId);
        throw new IllegalArgumentException(
                "A pipeline domain is required. Available domains: " + domains);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractNestedPolicyConfig(Map<String, Object> policyConfigs, String policyKey) {
        if (policyConfigs == null) {
            return Map.of();
        }
        Object raw = policyConfigs.get(policyKey);
        if (raw instanceof Map<?, ?> nested) {
            return (Map<String, Object>) nested;
        }
        return Map.of();
    }

    private String extractString(Object value, String fallback) {
        return value instanceof String stringValue ? stringValue : fallback;
    }

    private int extractInteger(Object value, int fallback) {
        return value instanceof Number numberValue ? numberValue.intValue() : fallback;
    }

    private record DomainResolution(String domainId, String domainName) {}
}
