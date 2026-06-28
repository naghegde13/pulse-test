package com.pulse.pipeline.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.audit.IngestionAuditColumns;
import com.pulse.common.exception.ResourceNotFoundException;
import com.pulse.pipeline.opengine.ConflictClassifier;
import com.pulse.pipeline.opengine.OpEngineException;
import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.SchemaBehaviorReader;
import com.pulse.pipeline.opengine.SchemaOpEngine;
import com.pulse.pipeline.model.InstancePortSchema;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SchemaConflict;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.InstancePortSchemaRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SchemaConflictRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.model.Dataset;
import com.pulse.sor.repository.DatasetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Topological schema propagation engine for pipeline compositions.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Resolve per-port input/output schemas via Kahn's algorithm.</li>
 *   <li>Apply the deterministic op-engine ({@code SchemaOpEngine}) over each blueprint's
 *       {@code schema_behavior} op-list; a blueprint with no op-list resolves via the legacy
 *       per-key rules (transitional shim until V153). There is <strong>no LLM fallback</strong>
 *       (ADR 0011/0013) — an unknown blueprint loud-fails.</li>
 *   <li>Detect conflicts using <strong>params-driven rules only</strong> (no dependency on
 *       non-guaranteed Phase 0 metadata fields).</li>
 *   <li>Handle user overrides on output ports.</li>
 *   <li>Mirror the primary output port schema back to {@code sub_pipeline_instances.output_schema} for
 *       backward compatibility with the existing composition API.</li>
 * </ul>
 */
@Service
public class SchemaPropagationService {

    private static final Logger log = LoggerFactory.getLogger(SchemaPropagationService.class);

    /**
     * Risk-#1 cutover flag (IMPL-builder §3, §7). When {@code true}, a blueprint with no
     * {@code schema_behavior} op-list AND no deterministic legacy rule LOUD-FAILS (the target
     * post-V153 behavior, SPEC #1 §B.3). When {@code false} (the default during the V153
     * transition), such a blueprint resolves via a deterministic <em>passthrough</em> fallback
     * (NOT an LLM) so the not-yet-op-listed catalog keeps working until V153 seeds every
     * blueprint's op-list — then the operator flips this flag. There is no LLM fallback in
     * either mode (ADR 0011/0013).
     */
    @Value("${pulse.builder.loud-fail-on-missing-op-list:false}")
    private boolean loudFailOnMissingOpList;

    /** Test/seam setter for the cutover flag (so unit tests can exercise the loud-fail target). */
    void setLoudFailOnMissingOpList(boolean v) { this.loudFailOnMissingOpList = v; }
    private static final Pattern SQL_IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Set<String> SQL_KEYWORDS = Set.of(
            "select", "from", "where", "and", "or", "not", "null", "is", "in",
            "like", "between", "as", "on", "join", "inner", "left", "right",
            "full", "outer", "group", "by", "order", "having", "true", "false",
            "case", "when", "then", "else", "end");

    private final SubPipelineInstanceRepository instanceRepo;
    private final PortWiringRepository wiringRepo;
    private final BlueprintRepository blueprintRepo;
    private final InstancePortSchemaRepository portSchemaRepo;
    private final SchemaConflictRepository conflictRepo;
    private final DatasetRepository datasetRepo;
    private final SchemaBehaviorReader schemaBehaviorReader;
    private final SchemaOpEngine schemaOpEngine;
    private final SourceSqlSchemaResolver sourceSqlSchemaResolver;
    private final ConflictClassifier conflictClassifier;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public SchemaPropagationService(SubPipelineInstanceRepository instanceRepo,
                                    PortWiringRepository wiringRepo,
                                    BlueprintRepository blueprintRepo,
                                    InstancePortSchemaRepository portSchemaRepo,
                                    SchemaConflictRepository conflictRepo,
                                    DatasetRepository datasetRepo,
                                    SchemaBehaviorReader schemaBehaviorReader,
                                    SchemaOpEngine schemaOpEngine,
                                    SourceSqlSchemaResolver sourceSqlSchemaResolver,
                                    ConflictClassifier conflictClassifier,
                                    JdbcTemplate jdbcTemplate,
                                    ObjectMapper objectMapper) {
        this.instanceRepo = instanceRepo;
        this.wiringRepo = wiringRepo;
        this.blueprintRepo = blueprintRepo;
        this.portSchemaRepo = portSchemaRepo;
        this.conflictRepo = conflictRepo;
        this.datasetRepo = datasetRepo;
        this.schemaBehaviorReader = schemaBehaviorReader;
        this.schemaOpEngine = schemaOpEngine;
        this.sourceSqlSchemaResolver = sourceSqlSchemaResolver;
        this.conflictClassifier = conflictClassifier;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    // ------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------

    @Transactional
    public PropagationSummary propagateFromVersion(String versionId) {
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId);
        return runTopologicalPass(versionId, instances, wirings, null);
    }

    @Transactional
    public PropagationSummary propagateFromInstance(String versionId, String instanceId) {
        // Re-running the whole version is the correct behavior for this engine: deterministic
        // rules plus hash-based change detection ensure that unchanged instances do no extra work.
        SubPipelineInstance entry = instanceRepo.findById(instanceId).orElse(null);
        if (entry == null || !versionId.equals(entry.getVersionId())) {
            return PropagationSummary.empty();
        }
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        List<PortWiring> wirings = wiringRepo.findByVersionIdOrderByCreatedAtAsc(versionId);
        return runTopologicalPass(versionId, instances, wirings, instanceId);
    }

    public SchemaGraph getSchemaGraph(String versionId) {
        List<SubPipelineInstance> instances = instanceRepo.findByVersionIdOrderByExecutionOrderAsc(versionId);
        if (instances.isEmpty()) {
            return new SchemaGraph(versionId, List.of());
        }
        List<String> instanceIds = instances.stream().map(SubPipelineInstance::getId).toList();
        List<InstancePortSchema> rows = portSchemaRepo.findByInstanceIdIn(instanceIds);
        Map<String, List<InstancePortSchema>> rowsByInstance = new HashMap<>();
        for (InstancePortSchema row : rows) {
            rowsByInstance.computeIfAbsent(row.getInstanceId(), k -> new ArrayList<>()).add(row);
        }
        List<InstanceSchemaView> views = new ArrayList<>();
        for (SubPipelineInstance inst : instances) {
            List<InstancePortSchema> portRows = rowsByInstance.getOrDefault(inst.getId(), List.of());
            List<PortView> ports = portRows.stream()
                    .map(r -> new PortView(
                            r.getPortName(),
                            r.getDirection(),
                            extractColumns(r.getSchemaJson()),
                            r.getSource(),
                            r.getOverride(),
                            r.getSchemaHash()))
                    .toList();
            views.add(new InstanceSchemaView(
                    inst.getId(),
                    inst.getName(),
                    inst.getBlueprintKey(),
                    inst.getSchemaStatus() != null ? inst.getSchemaStatus() : "unknown",
                    ports));
        }
        return new SchemaGraph(versionId, views);
    }

    public List<SchemaConflict> listConflicts(String versionId, boolean includeResolved) {
        if (includeResolved) {
            return conflictRepo.findByVersionIdOrderByCreatedAtDesc(versionId);
        }
        return conflictRepo.findByVersionIdAndResolutionStatusOrderByCreatedAtDesc(versionId, "open");
    }

    public ConflictResolutionPreview previewConflictResolution(String versionId,
                                                               String conflictId,
                                                               ResolutionRequest request) {
        SchemaConflict conflict = conflictRepo.findById(conflictId)
                .orElseThrow(() -> new ResourceNotFoundException("SchemaConflict", conflictId));
        if (!versionId.equals(conflict.getVersionId())) {
            throw new IllegalArgumentException("Conflict does not belong to this version");
        }
        ResolutionRequest effective = request != null
                ? request
                : new ResolutionRequest("accept_upstream", null, null);
        String resolutionType = effective.resolutionType() == null || effective.resolutionType().isBlank()
                ? "accept_upstream"
                : effective.resolutionType();
        Map<String, Object> details = conflict.getDetails() != null
                ? conflict.getDetails()
                : Map.of();
        String tier = stringValue(details.get("tier"));
        List<String> radius = stringList(details.get("impact_radius"));
        if (radius.isEmpty() && conflict.getPortName() != null) {
            radius = List.of(conflict.getInstanceId() + ":" + conflict.getPortName());
        } else if (radius.isEmpty()) {
            radius = List.of(conflict.getInstanceId());
        }
        if (tier == null || tier.isBlank()) {
            ConflictClassifier.Classification cls = conflictClassifier.classify(
                    conflict.getConflictType(),
                    stringValue(details.get("upstream_type")),
                    stringValue(details.get("downstream_type")),
                    true,
                    radius);
            tier = cls.tier().name().toLowerCase();
            radius = new ArrayList<>(cls.impactRadius());
        }
        List<String> mutations = switch (resolutionType) {
            case "accept_upstream" -> List.of("Mark conflict resolved and rerun schema propagation from impacted instance.");
            case "override" -> List.of("Apply provided output schema override to the conflicted port.",
                    "Rerun schema propagation from impacted instance.");
            case "flag_for_review" -> List.of("Keep conflict open and retain promotion gate.");
            default -> throw new IllegalArgumentException("Unknown resolutionType: " + resolutionType);
        };
        boolean willResolve = !"flag_for_review".equals(resolutionType);
        return new ConflictResolutionPreview(
                conflict.getId(),
                conflict.getConflictType(),
                tier,
                radius,
                resolutionType,
                willResolve,
                mutations,
                details);
    }

    @Transactional
    public SchemaConflict resolveConflict(String versionId, String conflictId, ResolutionRequest request) {
        SchemaConflict conflict = conflictRepo.findById(conflictId)
                .orElseThrow(() -> new ResourceNotFoundException("SchemaConflict", conflictId));
        if (!versionId.equals(conflict.getVersionId())) {
            throw new IllegalArgumentException("Conflict does not belong to this version");
        }
        if ("resolved".equals(conflict.getResolutionStatus())) {
            throw new IllegalStateException("Conflict already resolved");
        }

        String type = request.resolutionType();
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("resolutionType is required");
        }

        switch (type) {
            case "accept_upstream" -> {
                conflict.setResolutionStatus("resolved");
                conflict.setResolutionType("accept_upstream");
            }
            case "override" -> {
                if (conflict.getPortName() != null && request.override() != null
                        && request.override().get("columns") instanceof List<?> cols) {
                    applyOverride(conflict.getInstanceId(), conflict.getPortName(),
                            toColumnList(cols), request.notes(), "conflict-resolution");
                }
                conflict.setResolutionStatus("overridden");
                conflict.setResolutionType("override");
            }
            case "flag_for_review" -> {
                conflict.setResolutionStatus("open");
                conflict.setResolutionType("flag_for_review");
            }
            default -> throw new IllegalArgumentException("Unknown resolutionType: " + type);
        }
        conflict.setResolutionNotes(request.notes());
        conflict.setResolvedAt(Instant.now());
        conflict.setResolvedBy("stub-user");
        SchemaConflict saved = conflictRepo.save(conflict);

        if (!"flag_for_review".equals(type)) {
            propagateFromInstance(versionId, conflict.getInstanceId());
        }
        return saved;
    }

    @Transactional
    public InstancePortSchema setOverride(String versionId, String instanceId, String portName,
                                          OverrideRequest request) {
        SubPipelineInstance inst = instanceRepo.findById(instanceId)
                .orElseThrow(() -> new ResourceNotFoundException("SubPipelineInstance", instanceId));
        if (!versionId.equals(inst.getVersionId())) {
            throw new IllegalArgumentException("Instance does not belong to this version");
        }
        if (request.columns() == null) {
            throw new IllegalArgumentException("columns are required");
        }
        Optional<InstancePortSchema> existing = portSchemaRepo
                .findByInstanceIdAndPortNameAndDirection(instanceId, portName, "input");
        if (existing.isPresent()) {
            throw new IllegalStateException("Override on input ports is not supported");
        }

        InstancePortSchema saved = applyOverride(instanceId, portName,
                request.columns(), request.notes(), "stub-user");
        propagateFromInstance(versionId, instanceId);
        return saved;
    }

    @Transactional
    public InstancePortSchema clearOverride(String versionId, String instanceId, String portName) {
        InstancePortSchema row = portSchemaRepo
                .findByInstanceIdAndPortNameAndDirection(instanceId, portName, "output")
                .orElseThrow(() -> new ResourceNotFoundException("InstancePortSchema",
                        instanceId + "/" + portName + "/output"));
        row.setOverride(null);
        row.setSource("propagated");
        InstancePortSchema saved = portSchemaRepo.save(row);
        propagateFromInstance(versionId, instanceId);
        return saved;
    }

    // ------------------------------------------------------------
    // Topological engine
    // ------------------------------------------------------------

    private PropagationSummary runTopologicalPass(String versionId,
                                                  List<SubPipelineInstance> instances,
                                                  List<PortWiring> wirings,
                                                  String entryInstanceId) {
        if (instances.isEmpty()) {
            return PropagationSummary.empty();
        }

        // Reset "open" conflicts for every instance in the version so we re-evaluate cleanly.
        for (SubPipelineInstance inst : instances) {
            conflictRepo.deleteByInstanceIdAndResolutionStatus(inst.getId(), "open");
        }

        Map<String, SubPipelineInstance> byId = new LinkedHashMap<>();
        instances.forEach(inst -> byId.put(inst.getId(), inst));

        Map<String, Integer> inDegree = new HashMap<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        for (SubPipelineInstance inst : instances) {
            inDegree.put(inst.getId(), 0);
            adjacency.put(inst.getId(), new ArrayList<>());
        }
        for (PortWiring w : wirings) {
            if (!byId.containsKey(w.getSourceInstanceId()) || !byId.containsKey(w.getTargetInstanceId())) {
                continue;
            }
            adjacency.get(w.getSourceInstanceId()).add(w.getTargetInstanceId());
            inDegree.merge(w.getTargetInstanceId(), 1, Integer::sum);
        }

        Deque<String> queue = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : inDegree.entrySet()) {
            if (entry.getValue() == 0) queue.add(entry.getKey());
        }

        Set<String> processed = new LinkedHashSet<>();
        int conflictsEmitted = 0;

        for (SubPipelineInstance inst : instances) {
            inst.setSchemaStatus("pending");
        }
        instanceRepo.saveAll(instances);

        while (!queue.isEmpty()) {
            String instanceId = queue.pollFirst();
            SubPipelineInstance inst = byId.get(instanceId);
            Blueprint bp = inst.getBlueprintKey() == null ? null
                    : blueprintRepo.findByBlueprintKey(inst.getBlueprintKey()).orElse(null);

            int emitted = processInstance(versionId, inst, bp, wirings, byId);
            conflictsEmitted += emitted;
            processed.add(instanceId);

            for (String down : adjacency.getOrDefault(instanceId, List.of())) {
                int remaining = inDegree.merge(down, -1, Integer::sum);
                if (remaining == 0) queue.add(down);
            }
        }

        // Cycle residue: anything not processed is in a cycle.
        Set<String> cycleParticipants = new LinkedHashSet<>();
        for (String id : inDegree.keySet()) {
            if (!processed.contains(id)) cycleParticipants.add(id);
        }
        if (!cycleParticipants.isEmpty()) {
            List<String> participantList = new ArrayList<>(cycleParticipants);
            for (String id : cycleParticipants) {
                SubPipelineInstance inst = byId.get(id);
                inst.setSchemaStatus("conflict");
                instanceRepo.save(inst);
                emitConflict(versionId, id, null, "CYCLE_DETECTED",
                        Map.of("cycle_instance_ids", participantList));
                conflictsEmitted++;
            }
        }

        return new PropagationSummary(processed.size(), conflictsEmitted, !cycleParticipants.isEmpty());
    }

    private int processInstance(String versionId,
                                SubPipelineInstance inst,
                                Blueprint bp,
                                List<PortWiring> allWirings,
                                Map<String, SubPipelineInstance> byId) {
        int conflictsEmitted = 0;

        // 1. Resolve input ports from wirings; persist input port rows.
        List<PortResolution> inputs = resolveInputs(versionId, inst, bp, allWirings, byId);
        boolean missingUpstream = false;
        for (PortResolution r : inputs) {
            if (r.schema == null) {
                emitConflict(versionId, inst.getId(), r.portName, "MISSING_UPSTREAM",
                        Map.of(
                                "port_name", r.portName,
                                "upstream_instance_id",
                                r.upstreamInstanceId != null ? r.upstreamInstanceId : ""));
                clearPortSchema(inst.getId(), r.portName, "input");
                conflictsEmitted++;
                missingUpstream = true;
            } else {
                persistPortSchema(inst.getId(), r.portName, "input", r.schema, "propagated", null);
            }
        }

        if (missingUpstream) {
            // Keep prior derived output rows for non-ingestion nodes (stale-reference contract),
            // but clear them for read-source-rooted ingestion nodes with required upstream input.
            // This avoids leaving stale bronze output when a required source_input is missing.
            if (shouldClearOutputsOnMissingUpstream(inst, bp)) {
                clearDerivedOutputSchemas(inst, bp);
            }
            inst.setOutputSchema(wrapColumns(List.of()));
            inst.setSchemaStatus("conflict");
            instanceRepo.save(inst);
            return conflictsEmitted;
        }

        // 2. Params-driven MISSING_COLUMN detection.
        Map<String, Map<String, Object>> inputsByPort = new LinkedHashMap<>();
        for (PortResolution r : inputs) inputsByPort.put(r.portName, r.schema);
        conflictsEmitted += detectMissingColumns(versionId, inst, bp, inputsByPort);

        // 3. Derive outputs (with override fast-path) and detect type mismatches at joins / target mapping.
        Map<String, Object> primaryInput = findPrimaryInput(inst, bp, inputs);
        Map<String, Object> secondaryInput = findSecondaryInput(inst, bp, inputs);
        List<String> declaredOutputPorts = listDeclaredOutputPorts(bp);
        String primaryOutputPort = selectPrimaryOutputPort(declaredOutputPorts);

        int joinConflicts = detectJoinTypeMismatches(versionId, inst, primaryInput, secondaryInput);
        conflictsEmitted += joinConflicts;
        conflictsEmitted += detectSchemaNormalizationMismatches(versionId, inst, primaryInput);

        for (String portName : declaredOutputPorts) {
            Optional<InstancePortSchema> existing = portSchemaRepo
                    .findByInstanceIdAndPortNameAndDirection(inst.getId(), portName, "output");
            if (existing.isPresent() && existing.get().getOverride() != null) {
                // Override is authoritative; skip derivation but keep hash fresh.
                InstancePortSchema row = existing.get();
                row.setSchemaJson(wrapColumns(extractColumns(row.getOverride())));
                row.setSchemaHash(hash(row.getSchemaJson()));
                portSchemaRepo.save(row);
                continue;
            }
            Map<String, Object> outputSchema = deriveOutputSchema(
                    inst, bp, portName, primaryInput, secondaryInput);
            persistPortSchema(inst.getId(), portName, "output", outputSchema, "propagated", null);
        }

        // 4. Mirror primary output port schema to sub_pipeline_instances.output_schema.
        if (primaryOutputPort != null) {
            portSchemaRepo.findByInstanceIdAndPortNameAndDirection(inst.getId(), primaryOutputPort, "output")
                    .ifPresent(row -> {
                        inst.setOutputSchema(row.getSchemaJson());
                    });
        }

        // 5. Final status.
        if (conflictsEmitted > 0) {
            inst.setSchemaStatus("conflict");
        } else {
            inst.setSchemaStatus("clean");
        }
        instanceRepo.save(inst);

        return conflictsEmitted;
    }

    private List<PortResolution> resolveInputs(String versionId,
                                               SubPipelineInstance inst,
                                               Blueprint bp,
                                               List<PortWiring> allWirings,
                                               Map<String, SubPipelineInstance> byId) {
        List<String> declaredInputPorts = listDeclaredInputPorts(bp);
        List<PortResolution> result = new ArrayList<>();

        if (isReadSourceRootedIngestion(inst, bp)
            && hasOnlyOptionalIngestionUpstreamPort(declaredInputPorts)) {
            // Read-source-rooted ingestion node (e.g. FileIngestion). Its output schema is
            // derived solely from its source dataset (via sourceRootSchema during derivation)
            // plus audit columns; it does NOT consume an upstream input port for schema. Post
            // V156 (LCT-030) such a blueprint may declare an OPTIONAL upstream input port purely
            // as a wiring affordance — that port is not consumed for schema and must not raise
            // MISSING_UPSTREAM when unwired. Expose no consumable input so the bronze output
            // keeps its audit columns (which are otherwise omitted when a primary input is
            // present, see shouldOmitFileIngestionAuditForUpstream).
            return result;
        }

        if (declaredInputPorts.isEmpty()) {
            // Non-ingestion node with no declared input ports — resolve schema from
            // params.dataset_ids if available.
            Map<String, Object> ds = tryResolveDatasetSchema(inst);
            if (ds != null && !ds.isEmpty()) {
                result.add(new PortResolution("data", ds, null));
            }
            return result;
        }

        for (String portName : declaredInputPorts) {
            PortWiring wire = findWiring(allWirings, inst.getId(), portName);
            if (wire == null) {
                result.add(new PortResolution(portName, null, null));
                continue;
            }
            SubPipelineInstance upstream = byId.get(wire.getSourceInstanceId());
            if (upstream == null) {
                result.add(new PortResolution(portName, null, wire.getSourceInstanceId()));
                continue;
            }
            Map<String, Object> upstreamSchema = readUpstreamPortSchema(
                    upstream, wire.getSourcePortName());
            if (upstreamSchema == null || upstreamSchema.isEmpty()) {
                upstreamSchema = tryResolveDatasetSchema(upstream);
            }
            if (upstreamSchema == null || upstreamSchema.isEmpty()) {
                // upstream has no data yet — still record the port as a resolution with null schema.
                result.add(new PortResolution(portName, null, upstream.getId()));
            } else {
                result.add(new PortResolution(portName, upstreamSchema, upstream.getId()));
            }
        }
        return result;
    }

    private Map<String, Object> readUpstreamPortSchema(SubPipelineInstance upstream, String portName) {
        // Prefer per-port row on the upstream's output; fall back to sub_pipeline_instances.output_schema.
        Optional<InstancePortSchema> row = portSchemaRepo
                .findByInstanceIdAndPortNameAndDirection(upstream.getId(), portName, "output");
        if (row.isEmpty()) {
            row = portSchemaRepo
                    .findByInstanceIdAndPortNameAndDirection(upstream.getId(), "output", "output");
        }
        if (row.isPresent()) return row.get().getSchemaJson();
        return upstream.getOutputSchema();
    }

    /**
     * True iff this node is a source/ingestion node whose output schema is dataset-derived
     * rather than consumed from an upstream port. Read-source-rooted blueprints (e.g.
     * {@code FileIngestion}) may, post-V156 (LCT-030), declare an OPTIONAL upstream input port
     * purely as a wiring affordance; the bronze output schema stays source/dataset-derived and
     * that port is not consumed for schema. Such nodes must resolve from {@code params.dataset_ids}
     * and must NOT raise MISSING_UPSTREAM when the optional port is unwired. This mirrors the
     * source-rooting decision in {@link #deriveBaseOutputSchema} (op-list begins with read-source,
     * or — pre-op-list — a key ending in {@code Ingestion}).
     */
    private boolean isReadSourceRootedIngestion(SubPipelineInstance inst, Blueprint bp) {
        if (bp != null && schemaBehaviorReader.hasOpList(bp)) {
            return beginsWithReadSource(schemaBehaviorReader.read(bp).opList());
        }
        String key = inst.getBlueprintKey();
        return key != null && key.endsWith("Ingestion");
    }

    private boolean hasOnlyOptionalIngestionUpstreamPort(List<String> declaredInputPorts) {
        return declaredInputPorts.size() == 1 && "upstream".equals(declaredInputPorts.get(0));
    }

    private boolean shouldClearOutputsOnMissingUpstream(SubPipelineInstance inst, Blueprint bp) {
        return isReadSourceRootedIngestion(inst, bp)
                && !hasOnlyOptionalIngestionUpstreamPort(listDeclaredInputPorts(bp));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> tryResolveDatasetSchema(SubPipelineInstance inst) {
        if (inst.getParams() == null) return null;
        Object ids = inst.getParams().get("dataset_ids");
        if (!(ids instanceof List<?> idList) || idList.isEmpty()) return null;
        String first = idList.get(0).toString();
        Optional<Map<String, Object>> raw = readDatasetSchemaSnapshot(first);
        if (raw.isPresent()) return raw.get();
        return datasetRepo.findById(first)
                .map(Dataset::getSchemaSnapshot)
                .orElse(null);
    }

    @SuppressWarnings("unchecked")
    private Optional<Map<String, Object>> readDatasetSchemaSnapshot(String datasetId) {
        try {
            String raw = jdbcTemplate.queryForObject(
                    "select schema_snapshot::text from datasets where id = ?",
                    String.class,
                    datasetId);
            if (raw == null || raw.isBlank() || "null".equals(raw)) return Optional.empty();
            Object parsed = objectMapper.readValue(raw, Map.class);
            if (parsed instanceof Map<?, ?> map) {
                return Optional.of((Map<String, Object>) map);
            }
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        } catch (Exception e) {
            log.warn("Failed to read raw dataset schema snapshot for {}: {}", datasetId, e.getMessage());
        }
        return Optional.empty();
    }

    private PortWiring findWiring(List<PortWiring> wirings, String targetInstanceId, String targetPortName) {
        for (PortWiring w : wirings) {
            if (targetInstanceId.equals(w.getTargetInstanceId())
                    && targetPortName.equals(w.getTargetPortName())) return w;
        }
        // Fallback: wiring without matching port name but targeted at this instance.
        for (PortWiring w : wirings) {
            if (targetInstanceId.equals(w.getTargetInstanceId())) return w;
        }
        return null;
    }

    // ------------------------------------------------------------
    // Params-driven conflict detection (§9e)
    // ------------------------------------------------------------

    private int detectMissingColumns(String versionId, SubPipelineInstance inst, Blueprint bp,
                                     Map<String, Map<String, Object>> inputsByPort) {
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        List<MissingCheck> checks = collectMissingChecks(inst.getBlueprintKey(), params);
        if (checks.isEmpty()) return 0;

        List<String> declaredInputs = listDeclaredInputPorts(bp);
        String primary = primaryInputPortName(declaredInputs);
        String secondary = secondaryInputPortName(declaredInputs);

        int emitted = 0;
        for (MissingCheck check : checks) {
            String actualPort;
            if ("primary".equals(check.portName) || "left".equals(check.portName)) actualPort = primary;
            else if ("secondary".equals(check.portName) || "right".equals(check.portName)) actualPort = secondary;
            else actualPort = check.portName;

            Set<String> haystack = new LinkedHashSet<>();
            if (actualPort != null) {
                haystack.addAll(columnNames(inputsByPort.get(actualPort)));
            } else {
                for (Map<String, Object> in : inputsByPort.values()) {
                    haystack.addAll(columnNames(in));
                }
            }
            if (!haystack.contains(check.column)) {
                // A params-referenced column that is missing invalidates the consuming op
                // => required-by-op => breaking (ConflictClassifier, SPEC #1 §B.2).
                ConflictClassifier.Classification cls = conflictClassifier.classify(
                        ConflictClassifier.MISSING_COLUMN, null, null, true,
                        List.of(inst.getId()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("port_name", actualPort != null ? actualPort : "");
                details.put("missing_column", check.column);
                details.put("source_param", check.sourceParam);
                details.put("tier", cls.tier().name().toLowerCase());
                details.put("impact_radius", new ArrayList<>(cls.impactRadius()));
                emitConflict(versionId, inst.getId(), actualPort, "MISSING_COLUMN", details);
                emitted++;
            }
        }
        return emitted;
    }

    private String primaryInputPortName(List<String> declared) {
        if (declared == null || declared.isEmpty()) return null;
        for (String alias : List.of("primary", "left")) {
            if (declared.contains(alias)) return alias;
        }
        return declared.get(0);
    }

    private String secondaryInputPortName(List<String> declared) {
        if (declared == null || declared.size() < 2) return null;
        for (String alias : List.of("secondary", "right")) {
            if (declared.contains(alias)) return alias;
        }
        return declared.get(1);
    }

    @SuppressWarnings("unchecked")
    private List<MissingCheck> collectMissingChecks(String blueprintKey, Map<String, Object> params) {
        List<MissingCheck> out = new ArrayList<>();
        if (blueprintKey == null) return out;
        switch (blueprintKey) {
            case "GenericFilter" -> {
                appendListParamChecks(out, params, "columns_to_keep", "primary", "columns_to_keep");
                Object condition = params.get("filter_condition");
                if (condition instanceof String raw && !raw.isBlank()) {
                    for (String token : extractSqlIdentifiers(raw)) {
                        out.add(new MissingCheck("primary", token, "filter_condition"));
                    }
                }
                Object rawSql = params.get("raw_sql");
                if (rawSql instanceof String s && !s.isBlank()) {
                    for (String token : extractSqlIdentifiers(s)) {
                        out.add(new MissingCheck("primary", token, "raw_sql"));
                    }
                }
            }
            case "GenericJoin", "EnrichmentJoin" -> {
                appendListParamChecks(out, params, "left_join_keys", "left", "left_join_keys");
                appendListParamChecks(out, params, "right_join_keys", "right", "right_join_keys");
                Object joinKeys = params.get("join_keys");
                if (joinKeys instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Object left = map.containsKey("left_column") ? map.get("left_column") : map.get("left");
                            Object right = map.containsKey("right_column") ? map.get("right_column") : map.get("right");
                            if (left != null) {
                                out.add(new MissingCheck("left", left.toString(), "join_keys.left_column"));
                            }
                            if (right != null) {
                                out.add(new MissingCheck("right", right.toString(), "join_keys.right_column"));
                            }
                        } else if (item != null) {
                            out.add(new MissingCheck(null, item.toString(), "join_keys"));
                        }
                    }
                }
            }
            case "GenericAggregate" -> {
                appendListParamChecks(out, params, "group_by_columns", "primary", "group_by_columns");
                Object aggs = params.get("aggregations");
                if (aggs instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map) {
                            Object col = map.get("column");
                            if (col != null && !"*".equals(col)) {
                                out.add(new MissingCheck("primary", col.toString(), "aggregations.column"));
                            }
                        }
                    }
                }
            }
            case "GenericRouter" -> {
                Object preds = params.get("route_predicates");
                if (preds instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("column") != null) {
                            out.add(new MissingCheck("primary", map.get("column").toString(), "route_predicates.column"));
                        }
                    }
                }
            }
            case "PIIMasking" -> appendFirstPresentListParamChecks(out, params,
                    List.of("columns_to_mask", "pii_columns"), "primary");
            case "BronzeToSilverCleaning" -> {
                appendListParamChecks(out, params, "columns_to_trim", "primary", "columns_to_trim");
                Object casts = params.get("columns_to_cast");
                if (casts instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("column") != null) {
                            out.add(new MissingCheck("primary", map.get("column").toString(), "columns_to_cast.column"));
                        }
                    }
                }
                Object renameMap = params.get("rename_map");
                if (renameMap instanceof Map<?, ?> map) {
                    for (Object key : map.keySet()) {
                        if (key != null) {
                            out.add(new MissingCheck("primary", key.toString(), "rename_map"));
                        }
                    }
                }
            }
            case "DedupeAndMerge" -> {
                appendListParamChecks(out, params, "dedup_keys", "primary", "dedup_keys");
                Object order = params.get("order_by_columns");
                if (order instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("column") != null) {
                            out.add(new MissingCheck("primary", map.get("column").toString(), "order_by_columns.column"));
                        }
                    }
                }
            }
            case "IncrementalMerge" ->
                    appendListParamChecks(out, params, "merge_keys", "primary", "merge_keys");
            case "SCD2Dimension" -> {
                appendFirstPresentListParamChecks(out, params,
                        List.of("business_key_columns", "business_key"),
                        "primary");
                appendFirstPresentListParamChecks(out, params,
                        List.of("change_detection_columns", "tracked_columns"),
                        "primary");
            }
            case "SnapshotModel" -> {
                appendListParamChecks(out, params, "unique_key_columns", "primary", "unique_key_columns");
                Object updatedAt = params.get("updated_at_column");
                String strategy = params.get("strategy") != null ? params.get("strategy").toString() : "";
                if (updatedAt instanceof String s && !s.isBlank() && "timestamp".equals(strategy)) {
                    out.add(new MissingCheck("primary", s, "updated_at_column"));
                }
            }
            case "DQValidator" -> {
                Object expectations = params.get("expectations");
                if (expectations instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("column") != null) {
                            out.add(new MissingCheck("primary", map.get("column").toString(), "expectations.column"));
                        }
                    }
                }
            }
            case "SchemaNormalization" -> {
                Object target = params.get("target_schema");
                if (target instanceof List<?> list) {
                    for (Object item : list) {
                        if (item instanceof Map<?, ?> map && map.get("source_column") != null) {
                            out.add(new MissingCheck("primary", map.get("source_column").toString(),
                                    "target_schema.source_column"));
                        }
                    }
                } else if (params.get("mapping_rules") instanceof Map<?, ?> map) {
                    for (Object source : map.values()) {
                        if (source != null) {
                            out.add(new MissingCheck("primary", source.toString(), "mapping_rules"));
                        }
                    }
                }
            }
            default -> {
                if (blueprintKey.endsWith("Writer") || blueprintKey.endsWith("Publish")) {
                    Object mapping = params.get("target_column_mapping");
                    if (mapping instanceof Map<?, ?> map) {
                        for (Object key : map.keySet()) {
                            if (key != null) {
                                out.add(new MissingCheck("primary", key.toString(), "target_column_mapping"));
                            }
                        }
                    }
                }
            }
        }
        return out;
    }

    private void appendListParamChecks(List<MissingCheck> out, Map<String, Object> params,
                                       String paramKey, String portName, String sourceParam) {
        Object val = params.get(paramKey);
        if (!(val instanceof List<?> list)) return;
        for (Object v : list) {
            if (v != null) out.add(new MissingCheck(portName, v.toString(), sourceParam));
        }
    }

    private void appendFirstPresentListParamChecks(List<MissingCheck> out, Map<String, Object> params,
                                                   List<String> paramKeys, String portName) {
        for (String paramKey : paramKeys) {
            Object val = params.get(paramKey);
            if (val instanceof List<?> list) {
                for (Object v : list) {
                    if (v != null) out.add(new MissingCheck(portName, v.toString(), paramKey));
                }
                return;
            }
            if (val instanceof String s && !s.isBlank()) {
                out.add(new MissingCheck(portName, s, paramKey));
                return;
            }
        }
    }

    private List<String> extractSqlIdentifiers(String sql) {
        List<String> out = new ArrayList<>();
        Matcher m = SQL_IDENT.matcher(sql);
        while (m.find()) {
            String token = m.group();
            if (!SQL_KEYWORDS.contains(token.toLowerCase())) out.add(token);
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private int detectJoinTypeMismatches(String versionId, SubPipelineInstance inst,
                                          Map<String, Object> primary, Map<String, Object> secondary) {
        if (primary == null || secondary == null) return 0;
        String key = inst.getBlueprintKey();
        if (!"GenericJoin".equals(key) && !"EnrichmentJoin".equals(key)) return 0;
        int emitted = 0;
        Map<String, String> left = typeMap(primary);
        Map<String, String> right = typeMap(secondary);
        for (Map.Entry<String, String> entry : left.entrySet()) {
            String rightType = right.get(entry.getKey());
            if (rightType != null && !rightType.equalsIgnoreCase(entry.getValue())) {
                // A join-key type mismatch invalidates the join => required-by-op.
                ConflictClassifier.Classification cls = conflictClassifier.classify(
                        ConflictClassifier.TYPE_MISMATCH, entry.getValue(), rightType, true,
                        List.of(inst.getId()));
                Map<String, Object> details = new LinkedHashMap<>();
                details.put("port_name", "left");
                details.put("column_name", entry.getKey());
                details.put("upstream_type", entry.getValue());
                details.put("downstream_type", rightType);
                details.put("tier", cls.tier().name().toLowerCase());
                emitConflict(versionId, inst.getId(), "left", "TYPE_MISMATCH", details);
                emitted++;
            }
        }
        return emitted;
    }

    @SuppressWarnings("unchecked")
    private int detectSchemaNormalizationMismatches(String versionId, SubPipelineInstance inst,
                                                    Map<String, Object> primary) {
        if (primary == null) return 0;
        if (!"SchemaNormalization".equals(inst.getBlueprintKey())) return 0;
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        Object targetSchema = params.get("target_schema");
        Map<String, String> primaryTypes = typeMap(primary);
        int emitted = 0;
        if (targetSchema instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                Object source = entry.get("source_column");
                Object declaredType = entry.get("type");
                if (source == null || declaredType == null) continue;
                String srcType = primaryTypes.get(source.toString());
                if (srcType != null && !srcType.equalsIgnoreCase(declaredType.toString())) {
                    // A declared normalization target type the source cannot satisfy =>
                    // required-by-op; the classifier decides widening (partial) vs breaking.
                    ConflictClassifier.Classification cls = conflictClassifier.classify(
                            ConflictClassifier.TYPE_MISMATCH, srcType, declaredType.toString(),
                            true, List.of(inst.getId()));
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("port_name", "primary");
                    details.put("column_name", source.toString());
                    details.put("upstream_type", srcType);
                    details.put("downstream_type", declaredType.toString());
                    details.put("tier", cls.tier().name().toLowerCase());
                    emitConflict(versionId, inst.getId(), "primary", "TYPE_MISMATCH", details);
                    emitted++;
                }
            }
            return emitted;
        }
        if (!(params.get("mapping_rules") instanceof Map<?, ?> mappingRules)) return 0;
        for (Object source : mappingRules.values()) {
            if (source == null) continue;
            String sourceName = source.toString();
            if (!primaryTypes.containsKey(sourceName)) continue;
        }
        return emitted;
    }

    // ------------------------------------------------------------
    // Derivation rule set (§9f)
    // ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private Map<String, Object> deriveOutputSchema(SubPipelineInstance inst, Blueprint bp, String outputPort,
                                                   Map<String, Object> primary, Map<String, Object> secondary) {
        String key = inst.getBlueprintKey();
        Map<String, Object> params = inst.getParams() == null ? Map.of() : inst.getParams();
        Map<String, Object> base = deriveBaseOutputSchema(inst, bp, outputPort, primary, secondary, key, params);
        // Universal addenda — applied AFTER category-specific derivation so any
        // TRANSFORM/MODELING blueprint can use derived_columns / dropped_columns
        // without each one having to re-implement the logic.
        base = applyDerivedColumns(base, params);
        base = applyDroppedColumns(base, params);
        return base;
    }

    /**
     * Derive a blueprint instance's output schema (the design-time column authority).
     *
     * <p><b>Op-engine first (ADR 0011/0012/0013).</b> When the blueprint carries a
     * {@code schema_behavior} op-list (the new pinned shape, V153+), the deterministic
     * {@link SchemaOpEngine} walks the ops in order and produces the columns — codegen is
     * subordinate to these columns. There is <b>no LLM fallback</b>.
     *
     * <p><b>Transitional shim (Risk #1 mitigation, IMPL-builder §3).</b> Until V153 seeds
     * every catalog blueprint's op-list, a blueprint with no op-list resolves via the legacy
     * per-key rule below — the same deterministic rules as before, NOT an LLM. This keeps
     * existing pipelines working during the cutover and is removed once the catalog is fully
     * op-listed.
     *
     * <p><b>Loud-fail (SPEC #1 §B.3).</b> A blueprint with neither an op-list nor a legacy
     * rule fails loudly (a blocking conflict) rather than silently passing through or guessing.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> deriveBaseOutputSchema(SubPipelineInstance inst, Blueprint bp, String outputPort,
                                                       Map<String, Object> primary, Map<String, Object> secondary,
                                                       String key, Map<String, Object> params) {
        // 1. Op-engine path — the design-time column authority (the primary path post-V153).
        if (bp != null && schemaBehaviorReader.hasOpList(bp)) {
            SchemaBehaviorReader.BehaviorView view = schemaBehaviorReader.read(bp);
            // Movement ops (read-source) need the resolved dataset schema as their input;
            // supply the ingestion source schema as the engine's primary input when the
            // op-list begins with read-source and there is no upstream wiring.
            Map<String, Object> engineInput = primary;
            OpList opList = view.opList();
            if (beginsWithReadSource(opList) && isEmptySchema(primary)) {
                engineInput = sourceRootSchema(inst);
            }
            if (shouldOmitFileIngestionAuditForUpstream(key, opList, primary)) {
                opList = opList.withoutOp(OpVocabulary.ADD_AUDIT_COLUMNS);
            }
            return schemaOpEngine.applyOpListAsMap(
                    opList, view.surface(), params, engineInput, secondary);
        }

        // 2. Transitional shim — legacy deterministic per-key rules (no LLM).
        switch (key == null ? "" : key) {
            case "GenericFilter", "GenericRouter",
                 "BronzeToSilverCleaning", "DedupeAndMerge", "IncrementalMerge" ->
                    { return passThrough(primary); }
            case "PIIMasking" -> { return maskSchema(primary, params); }
            case "Derive" -> { return passThrough(primary); }
            case "GenericJoin", "EnrichmentJoin" -> { return mergeJoin(primary, secondary); }
            case "GenericAggregate" -> { return aggregateSchema(params, primary); }
            case "SchemaNormalization" -> { return normalizeSchema(params, primary); }
            case "SCD2Dimension" -> {
                // FIX #2: dbt-snapshot system columns (the rule was transposed pre-op-engine).
                return appendColumns(primary, List.of(
                        column("dbt_valid_from", "timestamp"),
                        column("dbt_valid_to", "timestamp"),
                        column("dbt_scd_id", "string"),
                        column("dbt_updated_at", "timestamp")));
            }
            case "SnapshotModel" -> {
                // FIX #3: take-periodic-snapshot columns (the rule was transposed pre-op-engine).
                return appendColumns(primary, List.of(
                        column("ds", "date"),
                        column("_pulse_processing_ts", "timestamp"),
                        column("_pulse_run_id", "string"),
                        column("_pulse_snapshot_model", "string")));
            }
            default -> {
                if (key != null && (key.endsWith("Writer") || key.endsWith("Publish")
                        || key.contains("DQ") || "DQValidator".equals(key))) {
                    return passThrough(primary);
                }
                if (key != null && key.endsWith("Ingestion")) {
                    return ingestionSchema(inst);
                }
                // Schema-preserving movement / control-plane families (sensors, schedules,
                // backfill/replay, rollback, remote-invocation, sinks): these do not DECIDE
                // columns — they read/move/orchestrate rows, so the deterministic rule is
                // passthrough. (Pre-op-engine these fell to the degenerate LLM fallback; the
                // op-engine resolves them via portless/sink ops once V153 seeds their op-lists.)
                if (key != null && isSchemaPreservingLegacyKey(key)) {
                    return passThrough(primary);
                }
                // 3. No op-list AND no legacy rule. The LLM fallback
                // (schemaInferenceService.inferOutputSchema) is DELETED per ADR 0011 — the
                // engine never guesses with an LLM. Behavior depends on the Risk-#1 cutover flag:
                //  - flag ON  (post-V153 target): LOUD-FAIL (SPEC #1 §B.3) — block until the
                //    blueprint's op-list exists.
                //  - flag OFF (transition default): deterministic PASSTHROUGH fallback so the
                //    not-yet-op-listed catalog keeps working until V153 lands.
                if (loudFailOnMissingOpList) {
                    throw new OpEngineException(
                            "blueprint '" + key + "' has no schema_behavior op-list and no "
                            + "deterministic rule — its metadata is incomplete (no LLM fallback)");
                }
                log.warn("blueprint '{}' has no op-list and no deterministic rule; using "
                        + "transitional passthrough fallback (will loud-fail post-V153)", key);
                return passThrough(primary);
            }
        }
    }

    /** True iff the schema wrapper has no columns. */
    private boolean isEmptySchema(Map<String, Object> schema) {
        return extractColumns(schema).isEmpty();
    }

    /**
     * Schema-preserving legacy blueprint families that the transitional shim treats as
     * passthrough (movement / control-plane: sensors, schedules, backfill/replay, rollback,
     * remote invocation, sinks). These do not DECIDE columns; pre-op-engine they fell to the
     * deleted LLM fallback. A genuinely-unknown TRANSFORM/MODELING key is NOT in this set, so
     * it still loud-fails until its op-list exists (V153).
     */
    private boolean isSchemaPreservingLegacyKey(String key) {
        if (key == null) return false;
        if (key.endsWith("Sensor") || key.endsWith("Sink") || key.endsWith("Hook")) return true;
        return switch (key) {
            case "BulkBackfill", "BackfillAndReplay",
                 "ScheduleAndTriggers", "RollbackOnFailure", "RemotePipelineInvocation",
                 "AdvanceTimeDimension" -> true;
            default -> false;
        };
    }

    /** True iff the op-list's first op is read-source (a source-rooted ingestion shape). */
    private boolean beginsWithReadSource(OpList opList) {
        return opList != null && !opList.ops().isEmpty()
                && OpVocabulary.READ_SOURCE.equals(opList.ops().get(0).op());
    }

    private boolean shouldOmitFileIngestionAuditForUpstream(String key,
                                                            OpList opList,
                                                            Map<String, Object> primary) {
        return "FileIngestion".equals(key)
                && beginsWithReadSource(opList)
                && !isEmptySchema(primary);
    }

    /**
     * Bronze ingestion schema = source dataset schema + PULSE audit columns.
     * The audit-column set is sourced from {@link IngestionAuditColumns} so it
     * stays in lockstep with the columns codegen emits at runtime.
     */
    private Map<String, Object> ingestionSchema(SubPipelineInstance inst) {
        List<Map<String, Object>> cols = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // Tag user-source columns with lineage="source" so the DAG can show
        // them differently from injected audit cols.
        for (Map<String, Object> col : extractColumns(sourceRootSchema(inst))) {
            Map<String, Object> tagged = new LinkedHashMap<>(col);
            tagged.putIfAbsent("lineage", "source");
            cols.add(tagged);
            if (col.get("name") != null) seen.add(col.get("name").toString());
        }

        // Append the canonical audit columns, skipping any already present in the
        // source. Re-running propagation against a previously-propagated schema
        // (which already carries the audit set) must stay idempotent rather than
        // duplicating audit columns.
        for (Map<String, Object> audit : IngestionAuditColumns.asColumnDescriptors()) {
            Object name = audit.get("name");
            if (name != null && seen.contains(name.toString())) continue;
            cols.add(audit);
        }
        return wrapColumns(cols);
    }

    private Map<String, Object> sourceRootSchema(SubPipelineInstance inst) {
        List<Map<String, Object>> sourceCols = List.of();
        if ("SourceSQL".equals(inst.getBlueprintKey()) && sourceSqlSchemaResolver != null) {
            sourceCols = sourceSqlSchemaResolver.resolveSourceColumns(inst).orElseGet(() ->
                    extractColumns(declaredOutputSchema(inst)));
        }
        if (sourceCols.isEmpty()) {
            Map<String, Object> ds = tryResolveDatasetSchema(inst);
            sourceCols = extractColumns(ds);
        }
        if (sourceCols.isEmpty() && inst.getOutputSchema() != null) {
            // Fall back to a manually-written schema (CompositionController.updateSchema).
            sourceCols = extractColumns(inst.getOutputSchema());
        }
        List<Map<String, Object>> taggedCols = new ArrayList<>();
        for (Map<String, Object> col : sourceCols) {
            Map<String, Object> tagged = new LinkedHashMap<>(col);
            tagged.putIfAbsent("lineage", "source");
            taggedCols.add(tagged);
        }
        return wrapColumns(taggedCols);
    }

    private Map<String, Object> declaredOutputSchema(SubPipelineInstance inst) {
        if (inst.getParams() == null) {
            return null;
        }
        Object declared = inst.getParams().get("declared_output_schema");
        if (declared == null) {
            declared = inst.getParams().get("declared_schema");
        }
        if (declared instanceof List<?> list) {
            return wrapColumns(toColumnList(list));
        }
        if (declared instanceof Map<?, ?> map) {
            Map<String, Object> typed = new LinkedHashMap<>();
            map.forEach((k, v) -> typed.put(String.valueOf(k), v));
            return typed;
        }
        return null;
    }

    /**
     * Mask resolver — non-masked columns pass through; masked columns gain a
     * lineage="masked:<strategy>" tag, the "masked" + "pii" tags, and a
     * transform descriptor so the DAG can display the lock icon and the
     * strategy. Type may be widened/narrowed per strategy (hash → string,
     * everything else preserves the source type).
     *
     * Accepts the canonical param shape plus two legacy shapes (legacy is a
     * back-compat fallback only — the canonical param wins on conflict):
     *   columns_to_mask: ["email", "ssn"] + masking_strategy: "hash"   — CANONICAL (PIIMasking catalog)
     *   pii_columns:     ["email", "ssn"]                              — legacy, default strategy "hash"
     *   mask_specs:      [{column: "email", strategy: "hash"}, ...]    — legacy per-column strategy
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> maskSchema(Map<String, Object> primary, Map<String, Object> params) {
        if (primary == null) return wrapColumns(List.of());
        Map<String, String> maskByColumn = new LinkedHashMap<>();
        // Canonical: columns_to_mask masked under a single masking_strategy.
        Object canonical = params.get("columns_to_mask");
        String canonicalStrategy = params.get("masking_strategy") != null
                ? params.get("masking_strategy").toString() : "hash";
        if (canonical instanceof List<?> list) {
            for (Object v : list) {
                if (v != null) maskByColumn.put(v.toString(), canonicalStrategy);
            }
        }
        // Legacy fallback (only fills columns the canonical param did not set).
        Object simple = params.get("pii_columns");
        if (simple instanceof List<?> list) {
            for (Object v : list) {
                if (v != null) maskByColumn.putIfAbsent(v.toString(), "hash");
            }
        }
        Object specs = params.get("mask_specs");
        if (specs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Object col = map.get("column");
                Object strategy = map.get("strategy");
                if (col != null) {
                    maskByColumn.putIfAbsent(col.toString(),
                            strategy != null ? strategy.toString() : "hash");
                }
            }
        }
        if (maskByColumn.isEmpty()) {
            return passThrough(primary);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> col : extractColumns(primary)) {
            Object name = col.get("name");
            if (name == null) {
                out.add(col);
                continue;
            }
            String strategy = maskByColumn.get(name.toString());
            if (strategy == null) {
                out.add(col);
                continue;
            }
            Map<String, Object> masked = new LinkedHashMap<>(col);
            masked.put("type", maskedTypeFor(strategy, col.get("type")));
            masked.put("lineage", "masked:" + strategy);
            masked.put("tags", appendTags(col.get("tags"), List.of("masked", "pii")));
            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("kind", "mask");
            transform.put("strategy", strategy);
            masked.put("transform", transform);
            out.add(masked);
        }
        return wrapColumns(out);
    }

    private String maskedTypeFor(String strategy, Object originalType) {
        String orig = originalType == null ? "string" : originalType.toString();
        return switch (strategy) {
            // SHA-256 hex digest → fixed-length string regardless of source type.
            case "hash" -> "string";
            // Everything else preserves source type (format-preserving by default).
            default -> orig;
        };
    }

    /**
     * Universal {@code derived_columns} addendum — appends columns described in
     * {@code params.derived_columns: [{name, type, expression, nullable?, description?}]}
     * onto the schema produced by the category-specific resolver. Available
     * on every TRANSFORM and MODELING blueprint, plus the dedicated
     * {@code Derive} blueprint.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyDerivedColumns(Map<String, Object> schema, Map<String, Object> params) {
        Object derived = params.get("derived_columns");
        if (!(derived instanceof List<?> list) || list.isEmpty()) return schema;
        List<Map<String, Object>> cols = new ArrayList<>(extractColumns(schema));
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> map)) continue;
            Object name = map.get("name");
            if (name == null) continue;
            Object type = map.get("type");
            Map<String, Object> col = new LinkedHashMap<>();
            col.put("name", name.toString());
            col.put("type", type != null ? type.toString() : "string");
            col.put("nullable", map.get("nullable") != null ? map.get("nullable") : true);
            if (map.get("description") != null) col.put("description", map.get("description"));
            col.put("lineage", "derived:expression");
            Map<String, Object> transform = new LinkedHashMap<>();
            transform.put("kind", "sql_expression");
            Object expr = map.get("expression");
            transform.put("expression", expr == null ? "" : expr.toString());
            col.put("transform", transform);
            col.put("tags", List.of("derived"));
            cols.add(col);
        }
        return wrapColumns(cols);
    }

    /**
     * Universal {@code dropped_columns} addendum — removes columns named in
     * {@code params.dropped_columns: [name, ...]} from the upstream output.
     * Applied after {@code applyDerivedColumns} so a derive+drop combination
     * can replace a column.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyDroppedColumns(Map<String, Object> schema, Map<String, Object> params) {
        Object dropped = params.get("dropped_columns");
        if (!(dropped instanceof List<?> list) || list.isEmpty()) return schema;
        Set<String> toDrop = new LinkedHashSet<>();
        for (Object v : list) if (v != null) toDrop.add(v.toString());
        if (toDrop.isEmpty()) return schema;
        List<Map<String, Object>> kept = new ArrayList<>();
        for (Map<String, Object> col : extractColumns(schema)) {
            Object name = col.get("name");
            if (name == null || !toDrop.contains(name.toString())) kept.add(col);
        }
        return wrapColumns(kept);
    }

    @SuppressWarnings("unchecked")
    private List<String> appendTags(Object existing, List<String> additions) {
        Set<String> merged = new LinkedHashSet<>();
        if (existing instanceof List<?> list) {
            for (Object t : list) if (t != null) merged.add(t.toString());
        }
        merged.addAll(additions);
        return new ArrayList<>(merged);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeJoin(Map<String, Object> primary, Map<String, Object> secondary) {
        Map<String, Map<String, Object>> merged = new LinkedHashMap<>();
        for (Map<String, Object> col : extractColumns(primary)) {
            if (col.get("name") != null) merged.put(col.get("name").toString(), col);
        }
        Map<String, String> primaryTypes = typeMap(primary);
        for (Map<String, Object> col : extractColumns(secondary)) {
            if (col.get("name") == null) continue;
            String name = col.get("name").toString();
            String ourType = primaryTypes.get(name);
            if (ourType == null) {
                merged.put(name, col);
            } else {
                // FIX #5 (legacy shim parity with JoinOp): a same-name collision keeps BOTH
                // sides under right_<name>, whether or not the types match (the old code
                // dropped the right column on a matching type).
                merged.put("right_" + name,
                        column("right_" + name, String.valueOf(col.get("type"))));
            }
        }
        return wrapColumns(new ArrayList<>(merged.values()));
    }

    private boolean isIntegerType(String type) {
        if (type == null) return false;
        return switch (type.toLowerCase()) {
            case "integer", "int", "long", "bigint", "smallint", "tinyint" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> aggregateSchema(Map<String, Object> params, Map<String, Object> primary) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> primaryTypes = typeMap(primary);
        Object groupBy = params.get("group_by_columns");
        if (groupBy instanceof List<?> list) {
            for (Object v : list) {
                if (v == null) continue;
                String name = v.toString();
                out.add(column(name, primaryTypes.getOrDefault(name, "string")));
            }
        }
        Object aggs = params.get("aggregations");
        if (aggs instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                String alias = entry.get("alias") != null ? entry.get("alias").toString() : null;
                if (alias == null) continue;
                String function = entry.get("function") != null ? entry.get("function").toString().toLowerCase() : "";
                String col = entry.get("column") != null ? entry.get("column").toString() : null;
                // FIX #6 (legacy shim parity with GroupAndAggregateOp): COUNT/COUNT_DISTINCT
                // -> long; SUM(integer) -> long, SUM(decimal) -> double; AVG -> double;
                // MIN/MAX -> source type.
                String srcType = primaryTypes.getOrDefault(col, "double");
                String type = switch (function) {
                    case "count", "count_distinct" -> "long";
                    case "sum" -> isIntegerType(srcType) ? "long" : "double";
                    case "avg" -> "double";
                    case "min", "max" -> srcType;
                    default -> "string";
                };
                out.add(column(alias, type));
            }
        }
        return wrapColumns(out);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeSchema(Map<String, Object> params, Map<String, Object> primary) {
        Object target = params.get("target_schema");
        if (target instanceof List<?> list && !list.isEmpty()) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> entry)) continue;
                Object name = entry.get("name") != null ? entry.get("name") : entry.get("target_column");
                Object type = entry.get("type");
                if (name == null || type == null) continue;
                out.add(column(name.toString(), type.toString()));
            }
            return wrapColumns(out);
        }
        if (!(params.get("mapping_rules") instanceof Map<?, ?> mappingRules) || mappingRules.isEmpty()) {
            return passThrough(primary);
        }

        boolean strictMode = Boolean.TRUE.equals(params.get("strict_mode"));
        List<Map<String, Object>> primaryColumns = extractColumns(primary);
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, String> renamedTargets = new HashMap<>();
        for (Map.Entry<?, ?> entry : mappingRules.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                renamedTargets.put(entry.getValue().toString(), entry.getKey().toString());
            }
        }

        for (Map<String, Object> sourceCol : primaryColumns) {
            Object name = sourceCol.get("name");
            if (name == null) continue;
            String sourceName = name.toString();
            String targetName = renamedTargets.getOrDefault(sourceName, sourceName);
            if (strictMode && !renamedTargets.containsKey(sourceName)) {
                continue;
            }
            Map<String, Object> renamed = new LinkedHashMap<>(sourceCol);
            renamed.put("name", targetName);
            out.add(renamed);
        }
        return wrapColumns(out);
    }

    // ------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------

    private Map<String, Object> findPrimaryInput(SubPipelineInstance inst, Blueprint bp,
                                                 List<PortResolution> inputs) {
        if (inputs.isEmpty()) return null;
        List<String> declared = listDeclaredInputPorts(bp);
        String preferred = declared.contains("primary") ? "primary"
                : declared.contains("left") ? "left"
                : declared.isEmpty() ? null
                : declared.get(0);
        if (preferred != null) {
            for (PortResolution r : inputs) {
                if (preferred.equals(r.portName)) return r.schema;
            }
        }
        return inputs.get(0).schema;
    }

    private Map<String, Object> findSecondaryInput(SubPipelineInstance inst, Blueprint bp,
                                                   List<PortResolution> inputs) {
        List<String> declared = listDeclaredInputPorts(bp);
        String preferred = declared.contains("secondary") ? "secondary"
                : declared.contains("right") ? "right"
                : declared.size() < 2 ? null
                : declared.get(1);
        if (preferred == null) return null;
        for (PortResolution r : inputs) {
            if (preferred.equals(r.portName)) return r.schema;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<String> listDeclaredInputPorts(Blueprint bp) {
        if (bp == null || bp.getInputPorts() == null) return List.of();
        List<String> out = new ArrayList<>();
        for (Map<String, Object> port : bp.getInputPorts()) {
            Object name = port.get("name");
            if (name != null) out.add(name.toString());
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private List<String> listDeclaredOutputPorts(Blueprint bp) {
        if (bp == null || bp.getOutputPorts() == null) return List.of("output");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> port : bp.getOutputPorts()) {
            Object name = port.get("name");
            if (name != null) out.add(name.toString());
        }
        return out.isEmpty() ? List.of("output") : out;
    }

    private String selectPrimaryOutputPort(List<String> declared) {
        if (declared == null || declared.isEmpty()) return "output";
        if (declared.size() == 1) return declared.get(0);
        if (declared.contains("output")) return "output";
        return declared.get(0);
    }

    private Map<String, Object> passThrough(Map<String, Object> primary) {
        if (primary == null) return wrapColumns(List.of());
        return wrapColumns(extractColumns(primary));
    }

    private Map<String, Object> appendColumns(Map<String, Object> primary, List<Map<String, Object>> extras) {
        List<Map<String, Object>> cols = new ArrayList<>(extractColumns(primary));
        cols.addAll(extras);
        return wrapColumns(cols);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractColumns(Map<String, Object> schema) {
        if (schema == null) return List.of();
        Object cols = schema.get("columns");
        if (!(cols instanceof List<?>)) {
            // Dataset schemaSnapshot stores its column list under "fields"
            // (see Dataset.schemaSnapshot), whereas propagated/instance schemas
            // use "columns". Accept either wrapper so dataset-bound source
            // schemas propagate instead of collapsing to audit-only columns.
            cols = schema.get("fields");
        }
        if (cols instanceof List<?> list) {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object c : list) {
                if (c instanceof Map<?, ?> map) out.add((Map<String, Object>) map);
            }
            return out;
        }
        return List.of();
    }

    private Map<String, Object> wrapColumns(List<Map<String, Object>> cols) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("columns", cols == null ? List.of() : cols);
        return out;
    }

    private Map<String, Object> column(String name, String type) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", name);
        out.put("type", type);
        return out;
    }

    private Map<String, String> typeMap(Map<String, Object> schema) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map<String, Object> col : extractColumns(schema)) {
            if (col.get("name") != null) {
                out.put(col.get("name").toString(),
                        col.get("type") == null ? "" : col.get("type").toString());
            }
        }
        return out;
    }

    private Set<String> columnNames(Map<String, Object> schema) {
        if (schema == null) return Set.of();
        Set<String> out = new LinkedHashSet<>();
        for (Map<String, Object> col : extractColumns(schema)) {
            if (col.get("name") != null) out.add(col.get("name").toString());
        }
        return out;
    }

    private List<Map<String, Object>> toColumnList(List<?> raw) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object c : raw) {
            if (c instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>();
                map.forEach((k, v) -> entry.put(String.valueOf(k), v));
                out.add(entry);
            }
        }
        return out;
    }

    private void clearPortSchema(String instanceId, String portName, String direction) {
        portSchemaRepo.findByInstanceIdAndPortNameAndDirection(instanceId, portName, direction)
                .ifPresent(portSchemaRepo::delete);
    }

    private void clearDerivedOutputSchemas(SubPipelineInstance inst, Blueprint bp) {
        for (String portName : listDeclaredOutputPorts(bp)) {
            portSchemaRepo.findByInstanceIdAndPortNameAndDirection(inst.getId(), portName, "output")
                    .ifPresent(row -> {
                        if (row.getOverride() == null) {
                            portSchemaRepo.delete(row);
                        }
                    });
        }
    }

    private InstancePortSchema persistPortSchema(String instanceId, String portName, String direction,
                                                 Map<String, Object> schema, String source,
                                                 Map<String, Object> override) {
        InstancePortSchema row = portSchemaRepo
                .findByInstanceIdAndPortNameAndDirection(instanceId, portName, direction)
                .orElseGet(() -> {
                    InstancePortSchema fresh = new InstancePortSchema();
                    fresh.setInstanceId(instanceId);
                    fresh.setPortName(portName);
                    fresh.setDirection(direction);
                    return fresh;
                });
        Map<String, Object> payload = schema == null ? wrapColumns(List.of()) : schema;
        row.setSchemaJson(payload);
        row.setSchemaHash(hash(payload));
        row.setSource(source);
        if (override != null) row.setOverride(override);
        return portSchemaRepo.save(row);
    }

    private InstancePortSchema applyOverride(String instanceId, String portName,
                                             List<Map<String, Object>> columns, String notes, String userId) {
        Map<String, Object> overridePayload = new LinkedHashMap<>();
        overridePayload.put("columns", columns);
        overridePayload.put("notes", notes);
        overridePayload.put("overridden_by", userId);
        overridePayload.put("overridden_at", Instant.now().toString());
        Map<String, Object> schema = wrapColumns(columns);
        InstancePortSchema row = portSchemaRepo
                .findByInstanceIdAndPortNameAndDirection(instanceId, portName, "output")
                .orElseGet(() -> {
                    InstancePortSchema fresh = new InstancePortSchema();
                    fresh.setInstanceId(instanceId);
                    fresh.setPortName(portName);
                    fresh.setDirection("output");
                    return fresh;
                });
        row.setSchemaJson(schema);
        row.setSchemaHash(hash(schema));
        row.setSource("override");
        row.setOverride(overridePayload);
        return portSchemaRepo.save(row);
    }

    private void emitConflict(String versionId, String instanceId, String portName,
                              String type, Map<String, Object> details) {
        SchemaConflict c = new SchemaConflict();
        c.setVersionId(versionId);
        c.setInstanceId(instanceId);
        c.setPortName(portName);
        c.setConflictType(type);
        c.setDetails(details);
        c.setResolutionStatus("open");
        conflictRepo.save(c);
    }

    private String hash(Map<String, Object> payload) {
        try {
            String canonical = objectMapper.writeValueAsString(payload);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] out = md.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : out) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item != null && !item.toString().isBlank()) {
                out.add(item.toString());
            }
        }
        return out;
    }

    // ------------------------------------------------------------
    // DTOs
    // ------------------------------------------------------------

    private static final class PortResolution {
        final String portName;
        final Map<String, Object> schema;
        final String upstreamInstanceId;

        PortResolution(String portName, Map<String, Object> schema, String upstreamInstanceId) {
            this.portName = portName;
            this.schema = schema;
            this.upstreamInstanceId = upstreamInstanceId;
        }
    }

    private record MissingCheck(String portName, String column, String sourceParam) {}

    public record PropagationSummary(int processed, int conflicts, boolean cycleDetected) {
        public static PropagationSummary empty() { return new PropagationSummary(0, 0, false); }
    }

    public record OverrideRequest(List<Map<String, Object>> columns, String notes) {}

    public record ResolutionRequest(String resolutionType, String notes, Map<String, Object> override) {}

    public record ConflictResolutionPreview(
            String conflictId,
            String conflictType,
            String classification,
            List<String> impactRadius,
            String resolutionType,
            boolean willResolve,
            List<String> mutations,
            Map<String, Object> details) {}

    public record SchemaGraph(String versionId, List<InstanceSchemaView> instances) {}

    public record InstanceSchemaView(
            String instanceId,
            String name,
            String blueprintKey,
            String schemaStatus,
            List<PortView> ports) {}

    public record PortView(
            String portName,
            String direction,
            List<Map<String, Object>> columns,
            String source,
            Map<String, Object> override,
            String schemaHash) {}
}
