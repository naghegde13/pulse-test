package com.pulse.codegen.service;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.git.model.GitRepo;
import com.pulse.git.repository.GitRepoRepository;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.service.OrchestrationNamespaceService;
import com.pulse.pipeline.service.OrchestrationNamespaceService.OrchestrationNamespace;
import com.pulse.sor.model.Domain;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.storage.contract.model.TableContract;
import com.pulse.storage.contract.service.TableContractService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class CompilePlanService {

    private final BlueprintRepository blueprintRepository;
    private final DeprecatedBlueprintCompatibilityService compat;
    private final DbtAssetRegistryService dbtAssetRegistryService;
    private final GitRepoRepository gitRepoRepository;
    private final DomainRepository domainRepository;
    private final TableContractService tableContractService;
    private final OrchestrationNamespaceService orchestrationNamespaceService;

    public CompilePlanService(BlueprintRepository blueprintRepository,
                              DeprecatedBlueprintCompatibilityService compat,
                              DbtAssetRegistryService dbtAssetRegistryService,
                              GitRepoRepository gitRepoRepository,
                              DomainRepository domainRepository,
                              TableContractService tableContractService,
                              OrchestrationNamespaceService orchestrationNamespaceService) {
        this.blueprintRepository = blueprintRepository;
        this.compat = compat;
        this.dbtAssetRegistryService = dbtAssetRegistryService;
        this.gitRepoRepository = gitRepoRepository;
        this.domainRepository = domainRepository;
        this.tableContractService = tableContractService;
        this.orchestrationNamespaceService = orchestrationNamespaceService;
    }

    public CompilePlanSnapshot build(Pipeline pipeline,
                                     String versionId,
                                     List<SubPipelineInstance> instances,
                                     List<PortWiring> wirings) {
        String domainSlug = slugify(
                pipeline.getDomainName() != null && !pipeline.getDomainName().isBlank()
                        ? pipeline.getDomainName()
                        : "default");
        String pipelineSlug = slugify(pipeline.getName());
        String namespace = String.format("%s/pipelines/%s", domainSlug, pipelineSlug);

        Map<String, Blueprint> blueprintsByKey = new LinkedHashMap<>();
        for (SubPipelineInstance instance : instances) {
            String blueprintKey = instance.getBlueprintKey();
            if (blueprintKey == null || blueprintsByKey.containsKey(blueprintKey)) {
                continue;
            }
            Blueprint blueprint = blueprintRepository.findByBlueprintKey(blueprintKey)
                    .orElseThrow(() -> new ResourceNotFoundException("Blueprint", blueprintKey));
            // PKT-0006: reject deprecated/deferred blueprints from compile plans
            if (compat.isCompatReadOnly(blueprint)) {
                throw new com.pulse.blueprint.exception.BlueprintCompatReadOnlyException(
                        blueprintKey, blueprint.getReplacementBlueprintKey());
            }
            blueprintsByKey.put(blueprintKey, blueprint);
        }

        Map<String, List<Map<String, Object>>> incomingEdges = new LinkedHashMap<>();
        for (PortWiring wiring : wirings) {
            incomingEdges.computeIfAbsent(wiring.getTargetInstanceId(), ignored -> new ArrayList<>())
                    .add(Map.of(
                            "sourceInstanceId", wiring.getSourceInstanceId(),
                            "sourcePortName", wiring.getSourcePortName(),
                            "targetPortName", wiring.getTargetPortName()
                    ));
        }

        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> silverPublishSources = new LinkedHashSet<>();
        Set<String> goldConsumers = new LinkedHashSet<>();
        String activeBranch = resolveActiveBranch(pipeline.getTenantId());
        boolean icebergEnabled = resolveIcebergEnabled(pipeline.getDomainId());

        for (SubPipelineInstance instance : instances) {
            Blueprint blueprint = blueprintsByKey.get(instance.getBlueprintKey());
            String emitStrategy = resolveEmitStrategy(blueprint);
            String compositionRole = resolveCompositionRole(blueprint);
            String layer = resolveLayer(instance, blueprint, compositionRole, emitStrategy);
            String executionContext = resolveExecutionContext(layer, compositionRole, emitStrategy);
            DbtAssetRegistryService.ReuseMatch reuseMatch = resolveReuseMatch(
                    pipeline.getDomainId(), instance, blueprint, emitStrategy, activeBranch);
            if (reuseMatch != null) {
                emitStrategy = reuseMatch.emitStrategy();
            }
            boolean publishesToGold = "gold".equals(layer);

            if ("silver".equals(layer)) {
                silverPublishSources.add(slugify(instance.getName()));
            }
            if (publishesToGold) {
                goldConsumers.add(slugify(instance.getName()));
            }

            Map<String, Object> node = new LinkedHashMap<>();
            node.put("instanceId", instance.getId());
            node.put("instanceName", instance.getName());
            node.put("blueprintKey", instance.getBlueprintKey());
            node.put("category", blueprint != null ? blueprint.getCategory().name() : "UNKNOWN");
            node.put("compositionRole", compositionRole);
            node.put("resolvedLayer", layer);
            node.put("executionContext", executionContext);
            node.put("emitStrategy", emitStrategy);
            if (reuseMatch != null) {
                node.put("reuseAsset", dbtAssetRegistryService.toApiPayload(reuseMatch.asset()));
                node.put("reuseDecision", dbtAssetRegistryService.toDecisionPayload(reuseMatch));
            }
            node.put("incomingEdges", incomingEdges.getOrDefault(instance.getId(), List.of()));
            node.put("params", instance.getParams() != null ? instance.getParams() : Map.of());
            node.put("validLayers", resolveValidLayers(blueprint, layer));
            node.put("artifactHints", resolveArtifactHints(layer, emitStrategy, icebergEnabled));
            List<String> partitioning = resolvePartitioning(instance);
            if (!partitioning.isEmpty()) {
                node.put("partitionBy", partitioning);
            }
            nodes.add(node);
        }

        Map<String, Object> goldPublishBoundary = new LinkedHashMap<>();
        goldPublishBoundary.put("mode", "bigquery_input_publish");
        goldPublishBoundary.put("inputDatasetTemplate",
                String.format("%s_%s_gold_input", slugify(pipeline.getTenantId()), domainSlug));
        goldPublishBoundary.put("servingDatasetTemplate",
                String.format("%s_%s_gold", slugify(pipeline.getTenantId()), domainSlug));
        goldPublishBoundary.put("silverSources", silverPublishSources);
        goldPublishBoundary.put("goldConsumers", goldConsumers);

        // ARCH-005: Build table contract authority summary for downstream consumers
        List<TableContract> activeContracts = tableContractService.findActiveContracts(versionId);
        List<Map<String, Object>> tableContractAuthority = activeContracts.stream()
                .map(c -> {
                    Map<String, Object> ref = new LinkedHashMap<>();
                    ref.put("contractId", c.getId());
                    ref.put("layer", c.getLayer());
                    ref.put("tableName", c.getTableName());
                    ref.put("schemaName", c.getSchemaName());
                    ref.put("catalogKind", c.getCatalogKind());
                    ref.put("relativeStoragePath", c.getRelativeStoragePath());
                    ref.put("producingInstanceId", c.getProducingInstanceId());
                    ref.put("tableFormat", c.getTableFormat());
                    return ref;
                })
                .toList();

        // ARCH-007: Resolve orchestration namespace for DAG ID and package naming
        OrchestrationNamespace orchestrationNamespace = orchestrationNamespaceService.resolve(pipeline);
        List<Map<String, Object>> advanceTimeContracts = instances.stream()
                .filter(instance -> "AdvanceTimeDimension".equals(instance.getBlueprintKey()))
                .map(instance -> buildAdvanceTimeContract(pipeline, instance))
                .toList();

        return new CompilePlanSnapshot(
                pipeline.getId(),
                versionId,
                pipeline.getDomainId(),
                pipeline.getDomainName(),
                namespace,
                nodes,
                goldPublishBoundary,
                tableContractAuthority,
                orchestrationNamespace,
                advanceTimeContracts
        );
    }

    private String resolveLayer(SubPipelineInstance instance,
                                Blueprint blueprint,
                                String compositionRole,
                                String emitStrategy) {
        if ("runtime_only".equals(emitStrategy) || "orchestration_sensor".equals(compositionRole)) {
            return "control_plane";
        }
        // ARCH-004 Phase 6: canonical column wins over params
        String canonicalLayer = instance.getLakeLayer();
        if (canonicalLayer != null && !canonicalLayer.isBlank()) {
            return canonicalLayer.toLowerCase();
        }
        Map<String, Object> params = instance.getParams() != null ? instance.getParams() : Map.of();
        Object explicitLayer = params.get("lake_layer");
        if (explicitLayer instanceof String layer && !layer.isBlank()) {
            return layer.toLowerCase();
        }
        if (blueprint != null && blueprint.getValidLayers() != null && !blueprint.getValidLayers().isEmpty()) {
            return blueprint.getValidLayers().get(0).toLowerCase();
        }

        String key = instance.getBlueprintKey() != null ? instance.getBlueprintKey() : "";
        return switch (key) {
            case "FileIngestion", "ApiIngestion", "StreamIngestion", "SnapshotIngestion", "CDCIngestion" -> "bronze";
            case "BronzeToSilverCleaning", "SchemaNormalization", "DQValidator" -> "silver";
            case "WideDenormalizedMart", "SCD2Dimension", "SnapshotModel" -> "gold";
            default -> blueprint != null && "MODELING".equals(blueprint.getCategory().name()) ? "gold" : "silver";
        };
    }

    private String resolveExecutionContext(String layer, String compositionRole, String emitStrategy) {
        if ("runtime_only".equals(emitStrategy) || "orchestration_sensor".equals(compositionRole)) {
            return "airflow";
        }
        return switch (layer) {
            case "bronze" -> "spark";
            case "silver" -> "dbt_spark";
            case "gold" -> "dbt_spark";
            default -> "dbt_spark";
        };
    }

    private String resolveEmitStrategy(Blueprint blueprint) {
        if (blueprint != null && blueprint.getEmitStrategy() != null && !blueprint.getEmitStrategy().isBlank()) {
            return blueprint.getEmitStrategy();
        }
        return "generate";
    }

    private String resolveCompositionRole(Blueprint blueprint) {
        if (blueprint != null && blueprint.getCompositionRole() != null && !blueprint.getCompositionRole().isBlank()) {
            return blueprint.getCompositionRole();
        }
        return "dataflow";
    }

    private DbtAssetRegistryService.ReuseMatch resolveReuseMatch(String domainId,
                                                                 SubPipelineInstance instance,
                                                                 Blueprint blueprint,
                                                                 String emitStrategy,
                                                                 String branch) {
        if (domainId == null || domainId.isBlank() || blueprint == null || !blueprint.isSupportsReuse()) {
            return null;
        }
        Map<String, Object> params = instance.getParams() != null ? instance.getParams() : Map.of();
        String businessConcept = stringParam(params, "business_concept", "");
        String grain = stringParam(params, "grain", "");
        String accessLevel = stringParam(params, "access_level", "");
        String schemaSignature = stringParam(params, "schema_signature", "");
        String requestedEmitStrategy = stringParam(params, "reuse_mode", "");
        String assetType = switch (instance.getBlueprintKey()) {
            case "SCD2Dimension" -> "snapshot";
            default -> "model";
        };
        return dbtAssetRegistryService
                .findReuseCandidate(
                        domainId,
                        businessConcept,
                        assetType,
                        grain,
                        accessLevel,
                        schemaSignature,
                        requestedEmitStrategy.isBlank() ? null : requestedEmitStrategy,
                        params,
                        branch)
                .orElse(null);
    }

    private String resolveActiveBranch(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || gitRepoRepository == null) {
            return "main";
        }
        try {
            return gitRepoRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream()
                    .filter(CompilePlanService::isTenantScoped)
                    .findFirst()
                    .map(CompilePlanService::resolveBranchFromRepo)
                    .orElse("main");
        } catch (Exception ignored) {
            return "main";
        }
    }

    private static boolean isTenantScoped(GitRepo repo) {
        Map<String, Object> metadata = repo.getMetadata();
        if (metadata != null) {
            Object scope = metadata.get("scope");
            if (scope != null && "TENANT".equalsIgnoreCase(scope.toString())) {
                return true;
            }
        }
        return repo.getPipelineId() == null && repo.getDomainId() == null;
    }

    private static String resolveBranchFromRepo(GitRepo repo) {
        Map<String, Object> metadata = repo.getMetadata();
        if (metadata != null && metadata.get("currentBranch") instanceof String s && !s.isBlank()) {
            return s;
        }
        String defaultBranch = repo.getDefaultBranch();
        return (defaultBranch == null || defaultBranch.isBlank()) ? "main" : defaultBranch;
    }

    private List<String> resolvePartitioning(SubPipelineInstance instance) {
        Map<String, Object> params = instance.getParams() != null ? instance.getParams() : Map.of();
        Object explicit = params.get("partition_by");
        if (explicit instanceof List<?> list && !list.isEmpty()) {
            List<String> out = new ArrayList<>();
            for (Object v : list) {
                if (v != null) out.add(v.toString());
            }
            return out;
        }
        if ("IncrementalMerge".equals(instance.getBlueprintKey())) {
            return List.of();
        }
        Map<String, Object> outputSchema = instance.getOutputSchema();
        if (outputSchema != null) {
            Object columns = outputSchema.get("columns");
            if (columns instanceof List<?> colList) {
                for (Object col : colList) {
                    if (col instanceof Map<?, ?> colMap) {
                        Object name = colMap.get("name");
                        if (name == null) continue;
                        String n = name.toString();
                        if (n.equalsIgnoreCase("event_date")
                                || n.equalsIgnoreCase("created_at")
                                || n.endsWith("_date")
                                || n.endsWith("_at")) {
                            return List.of(n);
                        }
                    }
                }
            }
        }
        return List.of();
    }

    private List<String> resolveValidLayers(Blueprint blueprint, String fallbackLayer) {
        if (blueprint != null && blueprint.getValidLayers() != null && !blueprint.getValidLayers().isEmpty()) {
            return blueprint.getValidLayers();
        }
        return List.of(fallbackLayer);
    }

    private Map<String, Object> resolveArtifactHints(String layer, String emitStrategy, boolean icebergEnabled) {
        Map<String, Object> hints = new LinkedHashMap<>();
        hints.put("emitStrategy", emitStrategy);
        String fileFormat = icebergEnabled ? "iceberg" : "delta";
        if ("gold".equals(layer)) {
            hints.put("publishBoundary", "bigquery_input_publish");
            hints.put("target", "bigquery");
        } else if ("control_plane".equals(layer)) {
            hints.put("target", "airflow_runtime");
        } else if ("silver".equals(layer)) {
            hints.put("target", "lakehouse_curated");
            hints.put("fileFormat", fileFormat);
        } else {
            hints.put("target", "lakehouse_raw");
            hints.put("fileFormat", fileFormat);
        }
        return hints;
    }

    private boolean resolveIcebergEnabled(String domainId) {
        if (domainId == null || domainId.isBlank() || domainRepository == null) return false;
        try {
            return domainRepository.findById(domainId)
                    .map(Domain::getBusinessDateConfig)
                    .map(cfg -> Boolean.TRUE.equals(cfg.get("iceberg_enabled")))
                    .orElse(false);
        } catch (Exception ignored) {
            return false;
        }
    }

    private String slugify(String name) {
        if (name == null || name.isBlank()) {
            return "unknown";
        }
        return name.toLowerCase()
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_|_$", "");
    }

    private String stringParam(Map<String, Object> params, String key, String fallback) {
        Object value = params.get(key);
        if (value == null) {
            return fallback;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? fallback : text;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> buildAdvanceTimeContract(Pipeline pipeline, SubPipelineInstance instance) {
        Map<String, Object> params = instance.getParams() != null ? instance.getParams() : Map.of();
        String targetScope = firstNonBlank(
                stringParam(params, "target_scope", ""),
                Boolean.TRUE.equals(params.get("advance_domain")) ? "domain" : "dataset");
        String scopeId = "domain".equalsIgnoreCase(targetScope)
                ? firstNonBlank(stringParam(params, "domain_id", ""), pipeline.getDomainId(), pipeline.getTenantId())
                : firstNonBlank(
                        stringParam(params, "dataset_id", ""),
                        stringParam(params, "dataset_name", ""),
                        instance.getId());
        String stateBindingRef = firstNonBlank(
                stringParam(params, "state_binding_ref", ""),
                "time_state:" + targetScope.toLowerCase() + ":" + slugify(scopeId));
        String variableKey = firstNonBlank(
                stringParam(params, "variable_key", ""),
                "pulse.time_state.%s.%s".formatted(
                        slugify(firstNonBlank(pipeline.getTenantId(), "default")),
                        pathSafe(stateBindingRef)));
        String calendarBindingRef = firstNonBlank(
                stringParam(params, "calendar_binding_ref", ""),
                "calendar:" + slugify(firstNonBlank(pipeline.getDomainId(), pipeline.getTenantId(), "default")));
        String calendarBundleUri = firstNonBlank(
                stringParam(params, "calendar_bundle_uri", ""),
                stringParam(params, "calendar_bundle_path", ""),
                "runtime/calendar/default-calendar-bundle.json");
        String requestedAsofExpr = firstNonBlank(
                stringParam(params, "requested_asof_expr", ""),
                stringParam(params, "requested_asof", ""));

        Map<String, Object> contract = new LinkedHashMap<>();
        contract.put("instanceId", instance.getId());
        contract.put("instanceName", instance.getName());
        contract.put("targetScope", targetScope);
        contract.put("scopeId", scopeId);
        contract.put("stateBindingRef", stateBindingRef);
        contract.put("variableKey", variableKey);
        contract.put("calendarBindingRef", calendarBindingRef);
        contract.put("calendarBundleUri", calendarBundleUri);
        contract.put("calendarBundleHash", stringParam(params, "calendar_bundle_hash", ""));
        contract.put("calendarId", firstNonBlank(stringParam(params, "calendar_id", ""), "US-FED"));
        contract.put("advanceMode", firstNonBlank(stringParam(params, "advance_mode", ""), "next_interval"));
        contract.put("requestedAsofExpr", requestedAsofExpr);
        contract.put("replayPolicy", firstNonBlank(stringParam(params, "replay_policy", ""), "reject_backward"));
        contract.put("initialValue", params.get("initial_value"));
        contract.put("initializationPolicy", firstNonBlank(
                stringParam(params, "initialization_policy", ""),
                params.containsKey("initial_value") ? "allow_projected_initial_value" : "require_existing"));
        contract.put("concurrencyPolicy", firstNonBlank(
                stringParam(params, "concurrency_policy", ""),
                "serialized_airflow"));
        contract.put("evidencePrefix", firstNonBlank(
                stringParam(params, "evidence_prefix", ""),
                "runtime-evidence/time-advances"));
        contract.put("evidenceRequired", booleanParam(params.get("evidence_required"), true));
        contract.put("grain", firstNonBlank(
                stringParam(params, "grain", ""),
                stringParam(params, "time_grain", ""),
                "DAILY_BUSINESS_DAY"));
        contract.put("timezone", firstNonBlank(
                stringParam(params, "timezone", ""),
                "America/New_York"));
        contract.put("notesTemplate", stringParam(params, "notes_template", stringParam(params, "notes", "")));
        contract.put("source", firstNonBlank(stringParam(params, "source", ""), "AdvanceTimeDimension"));
        contract.put("advancedBy", firstNonBlank(
                stringParam(params, "advanced_by", ""),
                "airflow:{{ dag.dag_id }}"));
        contract.put("poolName", "pulse_time_state_" + pathSafe(stateBindingRef));
        contract.put("requiredPoolSlots", 1);
        Object advanceConfig = params.get("advance_config");
        if (advanceConfig instanceof Map<?, ?> map && !map.isEmpty()) {
            contract.put("advanceConfig", new LinkedHashMap<>((Map<String, Object>) map));
        }
        return contract;
    }

    private boolean booleanParam(Object value, boolean fallback) {
        if (value == null) {
            return fallback;
        }
        if (value instanceof Boolean bool) {
            return bool;
        }
        String text = value.toString().trim();
        if (text.isEmpty()) {
            return fallback;
        }
        return Boolean.parseBoolean(text);
    }

    private String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate.trim();
            }
        }
        return "";
    }

    private String pathSafe(String value) {
        return slugify(value).replace(':', '_');
    }

    public record CompilePlanSnapshot(
            String pipelineId,
            String versionId,
            String domainId,
            String domainName,
            String namespace,
            List<Map<String, Object>> nodes,
            Map<String, Object> goldPublishBoundary,
            List<Map<String, Object>> tableContractAuthority,
            OrchestrationNamespace orchestrationNamespace,
            List<Map<String, Object>> advanceTimeContracts
    ) {}
}
