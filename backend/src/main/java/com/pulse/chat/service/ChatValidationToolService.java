package com.pulse.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.expression.service.ExpressionValidationService;
import com.pulse.expression.service.ExpressionValidationService.InputSchema;
import com.pulse.expression.service.ExpressionValidationService.SchemaColumn;
import com.pulse.expression.service.ExpressionValidationService.ValidationRequest;
import com.pulse.expression.service.ExpressionValidationService.ValidationResult;
import com.pulse.pipeline.model.Pipeline;
import com.pulse.pipeline.model.PortWiring;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.service.CompositionService;
import com.pulse.pipeline.service.CompositionService.CompositionView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Phase 9 (IMPL-ui-composition; WORKLIST-RESOLUTIONS §1 T8/T9/T11-T14) — the
 * deterministic, LLM-free logic behind the new discovery + validation read
 * tools. Pulled into a focused {@link Service} so it stays H2/unit-testable
 * independent of the {@link ChatToolExecutor} wiring.
 *
 * <p>Every method here is READ-ONLY: it never enqueues an op and never writes
 * canonical / Command-Log state. Tools return human-readable text the LLM
 * surfaces to the user.</p>
 */
@Service
public class ChatValidationToolService {

    private static final Logger log = LoggerFactory.getLogger(ChatValidationToolService.class);

    private final CompositionService compositionService;
    private final BlueprintRepository blueprintRepo;
    private final PipelineRepository pipelineRepo;
    private final ExpressionValidationService expressionValidationService;
    private final ChatReadToolHandler readToolHandler;
    private final ObjectMapper objectMapper;

    public ChatValidationToolService(CompositionService compositionService,
                                     BlueprintRepository blueprintRepo,
                                     PipelineRepository pipelineRepo,
                                     ExpressionValidationService expressionValidationService,
                                     ChatReadToolHandler readToolHandler,
                                     ObjectMapper objectMapper) {
        this.compositionService = compositionService;
        this.blueprintRepo = blueprintRepo;
        this.pipelineRepo = pipelineRepo;
        this.expressionValidationService = expressionValidationService;
        this.readToolHandler = readToolHandler;
        this.objectMapper = objectMapper;
    }

    // ==================================================================
    // T8 — get_composition_overview
    // ==================================================================

    /** Compact structural summary of a pipeline's active-version composition. */
    public String getCompositionOverview(String pipelineId) {
        if (pipelineId == null || pipelineId.isBlank()) {
            return "Error: pipeline_id is required.";
        }
        Pipeline pipeline = pipelineRepo.findById(pipelineId).orElse(null);
        if (pipeline == null) return "Pipeline not found: " + pipelineId;
        String versionId = pipeline.getActiveVersionId();
        if (versionId == null) return "Pipeline has no active version.";

        CompositionView comp = compositionService.getComposition(versionId);
        List<SubPipelineInstance> instances = comp.instances();
        List<PortWiring> wirings = comp.wirings();

        Set<String> layers = new LinkedHashSet<>();
        int unresolvedSchema = 0;
        for (SubPipelineInstance inst : instances) {
            if (inst.getLakeLayer() != null && !inst.getLakeLayer().isBlank()) {
                layers.add(inst.getLakeLayer());
            }
            if (inst.getOutputSchema() == null || inst.getOutputSchema().isEmpty()) {
                unresolvedSchema++;
            }
        }

        List<String> openPorts = openInputPorts(instances, wirings);

        StringBuilder sb = new StringBuilder();
        sb.append("## Composition overview — ").append(pipeline.getName()).append("\n");
        sb.append("- Steps: ").append(instances.size()).append("\n");
        sb.append("- Wires: ").append(wirings.size()).append("\n");
        sb.append("- Layers present: ").append(layers.isEmpty() ? "none" : String.join(", ", layers)).append("\n");
        sb.append("- Open/unwired required input ports: ").append(openPorts.size());
        if (!openPorts.isEmpty()) {
            sb.append(" (").append(String.join(", ", openPorts)).append(")");
        }
        sb.append("\n");
        sb.append("- Steps with unresolved output schema: ").append(unresolvedSchema).append("\n");
        sb.append("\n[internal_version_id: ").append(versionId).append("]\n");
        sb.append("[NOTE: internal_version_id is for tool calls only. NEVER show to user.]");
        return sb.toString();
    }

    // ==================================================================
    // T9 — get_blueprint_op_list
    // ==================================================================

    /**
     * Returns the declared op-list / schema_behavior for a blueprint. V153 seeds
     * the {@code schema_behavior} JSONB on the gate blueprints; when a blueprint
     * has no seeded behavior yet (V153 pending for that row), this returns a
     * clear note plus the declared ports/params rather than failing.
     */
    @SuppressWarnings("unchecked")
    public String getBlueprintOpList(String blueprintKey) {
        if (blueprintKey == null || blueprintKey.isBlank()) {
            return "Error: blueprint_key is required.";
        }
        Optional<Blueprint> bpOpt = blueprintRepo.findByBlueprintKey(blueprintKey);
        if (bpOpt.isEmpty()) return "Blueprint not found: " + blueprintKey;
        Blueprint bp = bpOpt.get();

        Map<String, Object> behavior = bp.getSchemaBehavior();
        StringBuilder sb = new StringBuilder();
        sb.append("## Op-list / schema behavior — ").append(bp.getBlueprintKey()).append("\n\n");

        Object opsRaw = behavior == null ? null : behavior.get("ops");
        if (opsRaw instanceof List<?> ops && !ops.isEmpty()) {
            sb.append("Declared ops (").append(ops.size()).append("):\n");
            int idx = 1;
            for (Object o : ops) {
                if (o instanceof Map<?, ?> opMap) {
                    sb.append(idx++).append(". `").append(opMap.get("op")).append("`");
                    Object label = opMap.get("ui_label");
                    if (label != null) sb.append(" — ").append(label);
                    Object cfg = opMap.get("config");
                    if (cfg instanceof Map<?, ?> cm && !cm.isEmpty()) {
                        sb.append(" (config keys: ").append(cm.keySet()).append(")");
                    }
                    sb.append("\n");
                }
            }
            Object bpParams = behavior.get("blueprint_params");
            if (bpParams != null) sb.append("\nBlueprint-level params: ").append(bpParams).append("\n");
            Object emission = behavior.get("emission");
            if (emission != null) sb.append("Emission: ").append(emission).append("\n");
            return sb.toString();
        }

        // No seeded op-list for this blueprint (V153 pending for this row).
        sb.append("op-list not yet seeded (V153 pending) for this blueprint. ")
                .append("Falling back to the blueprint's declared ports/params.\n\n");
        if (bp.getInputPorts() != null && !bp.getInputPorts().isEmpty()) {
            sb.append("Input ports: ");
            sb.append(joinPortNames(bp.getInputPorts())).append("\n");
        }
        if (bp.getOutputPorts() != null && !bp.getOutputPorts().isEmpty()) {
            sb.append("Output ports: ");
            sb.append(joinPortNames(bp.getOutputPorts())).append("\n");
        }
        if (bp.getParamsSchema() != null && !bp.getParamsSchema().isEmpty()) {
            sb.append("Params: ");
            List<String> names = new ArrayList<>();
            for (Map<String, Object> p : bp.getParamsSchema()) {
                Object n = p.get("name");
                if (n != null) names.add(n.toString() + (Boolean.TRUE.equals(p.get("required")) ? "*" : ""));
            }
            sb.append(String.join(", ", names)).append("  (* = required)\n");
        }
        return sb.toString();
    }

    // ==================================================================
    // T11 — validate_structure
    // ==================================================================

    /** Structural graph validation: orphans / cycles / reachability / unwired required ports. */
    public String validateStructure(Map<String, Object> args) {
        String versionId = resolveVersionId(args);
        if (versionId == null) {
            return "Error: provide version_id, or pipeline_id with an active version.";
        }
        StructureResult r = computeStructure(versionId);
        StringBuilder sb = new StringBuilder();
        sb.append("## validate_structure — ").append(r.ok ? "OK" : (r.issues.size() + " issue(s)")).append("\n");
        appendIssues(sb, r.issues);
        sb.append("\n[internal_version_id: ").append(versionId).append("]");
        return sb.toString();
    }

    // ==================================================================
    // T12 — validate_configuration
    // ==================================================================

    /** Per-step param/port completeness vs the Blueprint contract + runtime punch-list. */
    public String validateConfiguration(Map<String, Object> args, String tenantId) {
        String versionId = resolveVersionId(args);
        if (versionId == null) {
            return "Error: provide version_id, or pipeline_id with an active version.";
        }
        ConfigResult r = computeConfiguration(versionId, tenantId);
        StringBuilder sb = new StringBuilder();
        sb.append("## validate_configuration — ").append(r.ok ? "OK" : (r.issues.size() + " issue(s)")).append("\n");
        appendIssues(sb, r.issues);
        sb.append("\nTable-contract / runtime punch-list:\n");
        sb.append(indent(r.contractReadiness)).append("\n");
        sb.append("\n[internal_version_id: ").append(versionId).append("]");
        return sb.toString();
    }

    // ==================================================================
    // T13 — validate_plan (INTERIM, §7.16 #15)
    // ==================================================================

    /**
     * INTERIM Apply pre-flight = validate_structure + validate_configuration +
     * check_table_contract_readiness over the version graph. The real
     * deterministic-Builder compile pre-flight (ADR 0012/0013, specs #1/#2) is
     * NOT on this branch; the result is clearly labelled INTERIM until it lands.
     */
    public String validatePlan(Map<String, Object> args, String tenantId) {
        String versionId = resolveVersionId(args);
        if (versionId == null) {
            return "Error: provide version_id, or pipeline_id with an active version.";
        }
        StructureResult structure = computeStructure(versionId);
        ConfigResult config = computeConfiguration(versionId, tenantId);
        boolean ok = structure.ok && config.ok;

        StringBuilder sb = new StringBuilder();
        sb.append("## validate_plan (INTERIM) — ").append(ok ? "OK" : "ISSUES FOUND").append("\n");
        sb.append("> INTERIM check (WORKLIST-RESOLUTIONS §7.16 #15): structure + configuration + ")
                .append("contract-readiness. The full deterministic-Builder compile pre-flight ")
                .append("(ADR 0012/0013) is not yet on this branch and will replace this when it lands.\n\n");
        sb.append("### Structure\n");
        appendIssues(sb, structure.issues);
        sb.append("\n### Configuration\n");
        appendIssues(sb, config.issues);
        sb.append("\n### Contract readiness\n");
        sb.append(indent(config.contractReadiness)).append("\n");
        sb.append("\n[internal_version_id: ").append(versionId).append("]");
        return sb.toString();
    }

    // ==================================================================
    // T14 — validate_sql_expression (parse-only / declared, until spec #6)
    // ==================================================================

    /** Calcite parse-validate a derived-column expression / predicate / sql-model body. */
    @SuppressWarnings("unchecked")
    public String validateSqlExpression(Map<String, Object> args) {
        String expression = (String) args.get("expression");
        if (expression == null || expression.isBlank()) {
            return "Error: expression is required.";
        }
        String kind = args.get("kind") instanceof String k && !k.isBlank() ? k : "value";

        List<InputSchema> inputSchemas = new ArrayList<>();
        Object schemasRaw = args.get("input_schemas");
        if (schemasRaw instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof Map<?, ?> sm) {
                    Object port = sm.get("port_name");
                    List<SchemaColumn> cols = new ArrayList<>();
                    Object colsRaw = sm.get("columns");
                    if (colsRaw instanceof List<?> cl) {
                        for (Object c : cl) {
                            if (c instanceof Map<?, ?> cmap) {
                                Object name = cmap.get("name");
                                Object type = cmap.get("type");
                                if (name != null) {
                                    cols.add(new SchemaColumn(name.toString(),
                                            type == null ? null : type.toString()));
                                }
                            }
                        }
                    }
                    if (port != null) inputSchemas.add(new InputSchema(port.toString(), cols));
                }
            }
        }

        ValidationResult result = expressionValidationService.validate(
                new ValidationRequest(expression, kind, inputSchemas, null));

        StringBuilder sb = new StringBuilder();
        sb.append("## validate_sql_expression — ").append(result.valid() ? "PARSE-VALID" : "INVALID").append("\n");
        sb.append("> Parse-only / declared check. The schema-deriving CALCITE-PHASE-2 branch ")
                .append("depends on spec #6 and is not wired here yet.\n\n");
        if (result.diagnostics().isEmpty()) {
            sb.append("No diagnostics.\n");
        } else {
            sb.append("Diagnostics:\n");
            result.diagnostics().forEach(d ->
                    sb.append("- [").append(d.severity()).append("] ").append(d.code())
                            .append(": ").append(d.message()).append("\n"));
        }
        if (!result.referencedColumns().isEmpty()) {
            sb.append("\nReferenced columns: ");
            List<String> refs = new ArrayList<>();
            result.referencedColumns().forEach(rc ->
                    refs.add((rc.port() == null ? "" : rc.port() + ".") + rc.column()));
            sb.append(String.join(", ", refs)).append("\n");
        }
        return sb.toString();
    }

    // ==================================================================
    // Shared deterministic computation
    // ==================================================================

    record Issue(String code, String instance, String message) {}

    static final class StructureResult {
        boolean ok = true;
        final List<Issue> issues = new ArrayList<>();
    }

    static final class ConfigResult {
        boolean ok = true;
        final List<Issue> issues = new ArrayList<>();
        String contractReadiness = "(not evaluated)";
    }

    StructureResult computeStructure(String versionId) {
        StructureResult r = new StructureResult();
        CompositionView comp = compositionService.getComposition(versionId);
        List<SubPipelineInstance> instances = comp.instances();
        List<PortWiring> wirings = comp.wirings();

        if (instances.isEmpty()) {
            r.ok = false;
            r.issues.add(new Issue("EMPTY_COMPOSITION", null,
                    "The composition has no steps."));
            return finalizeOk(r);
        }

        // id <-> name + incident-degree bookkeeping.
        Set<String> hasIncoming = new HashSet<>();
        Set<String> hasOutgoing = new HashSet<>();
        Map<String, List<String>> adjacency = new HashMap<>();
        Set<String> ids = new LinkedHashSet<>();
        Map<String, String> idToName = new HashMap<>();
        for (SubPipelineInstance i : instances) {
            ids.add(i.getId());
            idToName.put(i.getId(), i.getName());
            adjacency.put(i.getId(), new ArrayList<>());
        }
        for (PortWiring w : wirings) {
            hasOutgoing.add(w.getSourceInstanceId());
            hasIncoming.add(w.getTargetInstanceId());
            if (adjacency.containsKey(w.getSourceInstanceId())) {
                adjacency.get(w.getSourceInstanceId()).add(w.getTargetInstanceId());
            }
        }

        // Orphans: a multi-step composition shouldn't have a step with no wires
        // on either side (a single-step composition is legitimately wireless).
        if (instances.size() > 1) {
            for (SubPipelineInstance i : instances) {
                if (!hasIncoming.contains(i.getId()) && !hasOutgoing.contains(i.getId())) {
                    r.issues.add(new Issue("ORPHAN_STEP", i.getName(),
                            "Step '" + i.getName() + "' has no wires in or out."));
                }
            }
        }

        // Cycles: DFS over the directed wiring graph.
        List<String> cycle = findCycle(ids, adjacency, idToName);
        if (cycle != null) {
            r.issues.add(new Issue("CYCLE_DETECTED", null,
                    "Composition contains a cycle: " + String.join(" -> ", cycle)));
        }

        // Reachability: from sources (no incoming) forward; anything unvisited is
        // unreachable. Only meaningful when at least one source exists.
        Set<String> sources = new LinkedHashSet<>();
        for (SubPipelineInstance i : instances) {
            if (!hasIncoming.contains(i.getId())) sources.add(i.getId());
        }
        if (!sources.isEmpty() && cycle == null) {
            Set<String> reachable = new HashSet<>();
            Deque<String> stack = new ArrayDeque<>(sources);
            while (!stack.isEmpty()) {
                String cur = stack.pop();
                if (!reachable.add(cur)) continue;
                for (String next : adjacency.getOrDefault(cur, List.of())) stack.push(next);
            }
            for (SubPipelineInstance i : instances) {
                if (!reachable.contains(i.getId())) {
                    r.issues.add(new Issue("UNREACHABLE_STEP", i.getName(),
                            "Step '" + i.getName() + "' is not reachable from any source step."));
                }
            }
        }

        // Unwired required input ports.
        Map<String, Set<String>> wiredTargetPorts = new HashMap<>();
        for (PortWiring w : wirings) {
            wiredTargetPorts.computeIfAbsent(w.getTargetInstanceId(), k -> new HashSet<>())
                    .add(w.getTargetPortName());
        }
        for (SubPipelineInstance i : instances) {
            for (String required : requiredInputPorts(i.getBlueprintKey())) {
                Set<String> wired = wiredTargetPorts.getOrDefault(i.getId(), Set.of());
                if (!wired.contains(required)) {
                    r.issues.add(new Issue("UNWIRED_REQUIRED_PORT", i.getName(),
                            "Required input port '" + required + "' on '" + i.getName() + "' is not wired."));
                }
            }
        }

        return finalizeOk(r);
    }

    ConfigResult computeConfiguration(String versionId, String tenantId) {
        ConfigResult r = new ConfigResult();
        CompositionView comp = compositionService.getComposition(versionId);
        List<SubPipelineInstance> instances = comp.instances();

        for (SubPipelineInstance i : instances) {
            Map<String, Object> params = i.getParams() == null ? Map.of() : i.getParams();
            for (String required : requiredParams(i.getBlueprintKey())) {
                Object v = params.get(required);
                boolean missing = v == null
                        || (v instanceof String s && s.isBlank())
                        || (v instanceof List<?> l && l.isEmpty());
                if (missing) {
                    r.issues.add(new Issue("MISSING_REQUIRED_PARAM", i.getName(),
                            "Step '" + i.getName() + "' is missing required param '" + required + "'."));
                }
            }
        }

        // Fold in the table-contract / runtime punch-list (check_table_contract_readiness).
        try {
            r.contractReadiness = readToolHandler.checkTableContractReadiness(
                    tenantId, new HashMap<>(Map.of("version_id", versionId)));
        } catch (Exception e) {
            r.contractReadiness = "(contract readiness unavailable: " + e.getMessage() + ")";
        }

        return finalizeOkConfig(r);
    }

    // ==================================================================
    // Helpers
    // ==================================================================

    private StructureResult finalizeOk(StructureResult r) {
        r.ok = r.issues.isEmpty();
        return r;
    }

    private ConfigResult finalizeOkConfig(ConfigResult r) {
        r.ok = r.issues.isEmpty();
        return r;
    }

    private void appendIssues(StringBuilder sb, List<Issue> issues) {
        if (issues.isEmpty()) {
            sb.append("No issues.\n");
            return;
        }
        for (Issue issue : issues) {
            sb.append("- [").append(issue.code()).append("] ");
            if (issue.instance() != null) sb.append(issue.instance()).append(": ");
            sb.append(issue.message()).append("\n");
        }
    }

    /** DFS cycle detection; returns the cycle path (by name) or null. */
    private List<String> findCycle(Set<String> ids, Map<String, List<String>> adjacency,
                                   Map<String, String> idToName) {
        Set<String> visited = new HashSet<>();
        Set<String> onStack = new LinkedHashSet<>();
        for (String id : ids) {
            List<String> cyc = dfsCycle(id, adjacency, visited, onStack, idToName);
            if (cyc != null) return cyc;
        }
        return null;
    }

    private List<String> dfsCycle(String node, Map<String, List<String>> adjacency,
                                  Set<String> visited, Set<String> onStack,
                                  Map<String, String> idToName) {
        if (onStack.contains(node)) {
            List<String> path = new ArrayList<>();
            boolean started = false;
            for (String s : onStack) {
                if (s.equals(node)) started = true;
                if (started) path.add(idToName.getOrDefault(s, s));
            }
            path.add(idToName.getOrDefault(node, node));
            return path;
        }
        if (visited.contains(node)) return null;
        visited.add(node);
        onStack.add(node);
        for (String next : adjacency.getOrDefault(node, List.of())) {
            List<String> cyc = dfsCycle(next, adjacency, visited, onStack, idToName);
            if (cyc != null) return cyc;
        }
        onStack.remove(node);
        return null;
    }

    /** Open (unwired) required input ports across the whole graph, "instance.port" labels. */
    private List<String> openInputPorts(List<SubPipelineInstance> instances, List<PortWiring> wirings) {
        Map<String, Set<String>> wiredTargetPorts = new HashMap<>();
        for (PortWiring w : wirings) {
            wiredTargetPorts.computeIfAbsent(w.getTargetInstanceId(), k -> new HashSet<>())
                    .add(w.getTargetPortName());
        }
        List<String> open = new ArrayList<>();
        for (SubPipelineInstance i : instances) {
            Set<String> wired = wiredTargetPorts.getOrDefault(i.getId(), Set.of());
            for (String required : requiredInputPorts(i.getBlueprintKey())) {
                if (!wired.contains(required)) open.add(i.getName() + "." + required);
            }
        }
        return open;
    }

    /**
     * Required input ports for a blueprint. A port is required unless explicitly
     * marked {@code optional:true} / {@code required:false}.
     */
    private Set<String> requiredInputPorts(String blueprintKey) {
        Blueprint bp = blueprintRepo.findByBlueprintKey(blueprintKey).orElse(null);
        if (bp == null || bp.getInputPorts() == null) return Set.of();
        Set<String> required = new LinkedHashSet<>();
        for (Map<String, Object> port : bp.getInputPorts()) {
            Object name = port.get("name");
            if (name == null) continue;
            boolean optional = Boolean.TRUE.equals(port.get("optional"))
                    || Boolean.FALSE.equals(port.get("required"));
            if (!optional) required.add(name.toString());
        }
        return required;
    }

    /** Required params for a blueprint (params_schema entries with required:true). */
    private Set<String> requiredParams(String blueprintKey) {
        Blueprint bp = blueprintRepo.findByBlueprintKey(blueprintKey).orElse(null);
        if (bp == null || bp.getParamsSchema() == null) return Set.of();
        Set<String> required = new LinkedHashSet<>();
        for (Map<String, Object> p : bp.getParamsSchema()) {
            Object name = p.get("name");
            // Only flag user-tier required params; derived params are platform-resolved.
            boolean derived = "derived".equals(p.get("tier"));
            if (name != null && Boolean.TRUE.equals(p.get("required")) && !derived) {
                required.add(name.toString());
            }
        }
        return required;
    }

    private String joinPortNames(List<Map<String, Object>> ports) {
        List<String> names = new ArrayList<>();
        for (Map<String, Object> p : ports) {
            Object n = p.get("name");
            if (n != null) names.add(n.toString());
        }
        return String.join(", ", names);
    }

    private String indent(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n", -1)) sb.append("  ").append(line).append("\n");
        return sb.toString().stripTrailing();
    }

    /** Resolve a version id from version_id, or the pipeline's active version. */
    private String resolveVersionId(Map<String, Object> args) {
        String versionId = (String) args.get("version_id");
        if (versionId != null && !versionId.isBlank()) return versionId;
        String pipelineId = (String) args.get("pipeline_id");
        if (pipelineId == null || pipelineId.isBlank()) return null;
        return pipelineRepo.findById(pipelineId).map(Pipeline::getActiveVersionId).orElse(null);
    }
}
