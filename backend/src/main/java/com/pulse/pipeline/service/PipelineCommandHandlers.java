package com.pulse.pipeline.service;

import com.pulse.command.model.CommandLog;
import com.pulse.command.service.CommandService;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PipelineStage;
import com.pulse.pipeline.model.PipelineVersion;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.runtime.model.RuntimeAuthority;
import com.pulse.runtime.model.RuntimePersona;
import com.pulse.runtime.service.RuntimeAuthorityService;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class PipelineCommandHandlers {

    public static final String CREATE_PIPELINE = "pipeline.create";
    public static final String UPDATE_PIPELINE = "pipeline.update";
    public static final String DELETE_PIPELINE = "pipeline.delete";
    public static final String TRANSITION_STAGE = "pipeline.transition";
    public static final String CREATE_REVISION = "pipeline.createRevision";

    private final CommandService commandService;
    private final PipelineRepository pipelineRepository;
    private final PipelineVersionRepository versionRepository;
    private final RuntimeAuthorityService runtimeAuthorityService;

    public PipelineCommandHandlers(CommandService commandService,
                                   PipelineRepository pipelineRepository,
                                   PipelineVersionRepository versionRepository,
                                   RuntimeAuthorityService runtimeAuthorityService) {
        this.commandService = commandService;
        this.pipelineRepository = pipelineRepository;
        this.versionRepository = versionRepository;
        this.runtimeAuthorityService = runtimeAuthorityService;
    }

    @PostConstruct
    void register() {
        commandService.registerHandler(CREATE_PIPELINE, this::handleCreate);
        commandService.registerHandler(UPDATE_PIPELINE, this::handleUpdate);
        commandService.registerHandler(DELETE_PIPELINE, this::handleDelete);
        commandService.registerHandler(TRANSITION_STAGE, this::handleTransition);
        commandService.registerHandler(CREATE_REVISION, this::handleCreateRevision);
    }

    private Object handleCreate(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        Pipeline pipeline = new Pipeline();
        pipeline.setTenantId(cmd.getTenantId());
        pipeline.setName((String) p.get("name"));
        pipeline.setDescription((String) p.get("description"));
        pipeline.setDomainName((String) p.get("domainName"));
        pipeline.setDomainId((String) p.get("domainId"));
        String defaultStorageBackend = (String) p.get("defaultStorageBackend");
        if (defaultStorageBackend == null || defaultStorageBackend.isBlank()) {
            defaultStorageBackend = deriveDefaultStorageBackend();
        }
        pipeline.setDefaultStorageBackend(defaultStorageBackend);
        pipeline.setCreatedBy(cmd.getActorId());
        pipeline = pipelineRepository.save(pipeline);

        PipelineVersion version = new PipelineVersion();
        version.setPipelineId(pipeline.getId());
        version.setRevision(1);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy(cmd.getActorId());
        version.setChangeSummary("Initial revision");
        version = versionRepository.save(version);

        pipeline.setActiveVersionId(version.getId());
        pipelineRepository.save(pipeline);

        return Map.of(
                "createdAggregateType", "pipeline",
                "createdAggregateId", pipeline.getId(),
                "pipelineId", pipeline.getId(),
                "versionId", version.getId());
    }

    private String deriveDefaultStorageBackend() {
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

    private Object handleUpdate(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        Pipeline pipeline = pipelineRepository.findById(cmd.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", cmd.getAggregateId()));
        if (p.containsKey("name")) pipeline.setName((String) p.get("name"));
        if (p.containsKey("description")) pipeline.setDescription((String) p.get("description"));
        // ARCH-010: only assign when explicitly present so omitted updates preserve.
        if (p.containsKey("defaultStorageBackend")) {
            String v = (String) p.get("defaultStorageBackend");
            if (v != null && !v.isBlank()) {
                pipeline.setDefaultStorageBackend(v);
            }
        }
        pipelineRepository.save(pipeline);
        return Map.of("pipelineId", pipeline.getId());
    }

    private Object handleDelete(CommandLog cmd) {
        Pipeline pipeline = pipelineRepository.findById(cmd.getAggregateId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", cmd.getAggregateId()));
        var versions = versionRepository.findByPipelineIdOrderByCreatedAtDesc(pipeline.getId());
        boolean allEngineering = versions.stream()
                .allMatch(v -> v.getLifecycleStage() == PipelineStage.ENGINEERING);
        if (!allEngineering) {
            throw new IllegalArgumentException("Only pipelines with all versions in ENGINEERING can be deleted");
        }
        versionRepository.deleteAll(versions);
        pipelineRepository.delete(pipeline);
        return Map.of("deleted", pipeline.getId());
    }

    private Object handleTransition(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String versionId = (String) p.get("versionId");
        String targetStageStr = (String) p.get("targetStage");
        PipelineStage targetStage = PipelineStage.valueOf(targetStageStr);

        PipelineVersion version = versionRepository.findById(versionId)
                .orElseThrow(() -> new ResourceNotFoundException("PipelineVersion", versionId));

        PipelineStage current = version.getLifecycleStage();
        if (!current.canTransitionTo(targetStage)) {
            throw new IllegalArgumentException(
                    "Cannot transition from " + current + " to " + targetStage
                    + ". Allowed: " + current.allowedTransitions());
        }
        version.setLifecycleStage(targetStage);
        versionRepository.save(version);

        // When a revision reaches the terminal PULSE state (PUBLISHED) it becomes the
        // active version. PULSE does not observe further enterprise-CD deployments;
        // there are no post-PUBLISHED PULSE stages to gate on.
        if (targetStage == PipelineStage.PUBLISHED) {
            Pipeline pipeline = pipelineRepository.findById(version.getPipelineId())
                    .orElseThrow(() -> new ResourceNotFoundException("Pipeline", version.getPipelineId()));
            pipeline.setActiveVersionId(version.getId());
            pipelineRepository.save(pipeline);
        }

        return Map.of("versionId", versionId, "from", current.name(), "to", targetStage.name());
    }

    private Object handleCreateRevision(CommandLog cmd) {
        Map<String, Object> p = cmd.getPayload();
        String pipelineId = cmd.getAggregateId();

        var existing = versionRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
        boolean hasEngineering = existing.stream()
                .anyMatch(v -> v.getLifecycleStage() == PipelineStage.ENGINEERING);
        if (hasEngineering) {
            throw new IllegalArgumentException("A revision is already in engineering");
        }

        int nextRevision = existing.stream()
                .mapToInt(PipelineVersion::getRevision)
                .max().orElse(0) + 1;

        PipelineVersion version = new PipelineVersion();
        version.setPipelineId(pipelineId);
        version.setRevision(nextRevision);
        version.setLifecycleStage(PipelineStage.ENGINEERING);
        version.setCreatedBy(cmd.getActorId());
        version.setChangeSummary((String) p.get("changeSummary"));
        version = versionRepository.save(version);

        return Map.of("versionId", version.getId(), "revision", nextRevision);
    }
}
