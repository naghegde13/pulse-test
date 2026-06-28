package com.pulse.chat.orchestration;

import com.pulse.blueprint.model.Blueprint;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * P3 — the op-emitting mutation tier (IMPL-ui-composition Phase 3; 06 §E).
 *
 * <p>Given a mutation tool name + args + the current STAGING graph, this service
 * VALIDATES (JSON-schema shape + semantic: blueprint addable, ref/port existence)
 * and returns exactly ONE {@link PlanOperation}. It NEVER writes the canonical
 * graph or the Command Log — that is {@code apply_plan}'s sole job (P4). The
 * validation logic lives here (testable on H2), NOT in the langgraph4j types.</p>
 *
 * <p>The 7 mutation tools (snake_case) map 1:1 onto the op union (06 §E):</p>
 * <ul>
 *   <li>{@code add_blueprint_instance} → {@code AddInstances} (REQUIRED reasoning;
 *       rejects deprecated / orchestration-policy blueprints; NO initial params, 3-F4)</li>
 *   <li>{@code wire_ports} → {@code MergeWiring} (REQUIRED reasoning)</li>
 *   <li>{@code set_params} → {@code UpdateInstance}</li>
 *   <li>{@code remove_instance} → {@code RemoveInstance}</li>
 *   <li>{@code remove_wire} → {@code RemoveWiring}</li>
 *   <li>{@code rename_instance} → {@code Rename}</li>
 *   <li>{@code set_pipeline_setting} → {@code SetPipelineSetting}</li>
 * </ul>
 *
 * <p>{@code instanceRef} is the instance NAME end-to-end. Bad refs / deprecated
 * blueprints raise {@link MutationValidationException}, which the driver turns
 * into a {@code tool_result} error WITHOUT enqueuing an op.</p>
 */
@Component
public class MutationToolService {

    /** The closed set of mutation tool names this tier owns. */
    public static final List<String> MUTATION_TOOLS = List.of(
            "add_blueprint_instance",
            "wire_ports",
            "set_params",
            "remove_instance",
            "remove_wire",
            "rename_instance",
            "set_pipeline_setting");

    private final BlueprintRepository blueprintRepo;
    private final DeprecatedBlueprintCompatibilityService compat;

    @Autowired
    public MutationToolService(BlueprintRepository blueprintRepo,
                               DeprecatedBlueprintCompatibilityService compat) {
        this.blueprintRepo = blueprintRepo;
        this.compat = compat;
    }

    /** A validation failure — surfaced as a tool_result error, no op enqueued. */
    public static class MutationValidationException extends RuntimeException {
        public MutationValidationException(String message) { super(message); }
    }

    /** Whether {@code toolName} is one of the 7 op-emitting mutation tools. */
    public static boolean isMutationTool(String toolName) {
        return MUTATION_TOOLS.contains(toolName);
    }

    /**
     * Validate + map a single mutation tool call to ONE {@link PlanOperation}.
     * {@code staging} is the current effective STAGING graph (canonical clone +
     * pending ops) used for semantic checks (ref existence, port-type, etc.).
     * Does NOT mutate {@code staging}.
     */
    @SuppressWarnings("unchecked")
    public PlanOperation toOperation(String toolName, Map<String, Object> args, StagingGraph staging) {
        if (args == null) args = Map.of();
        StagingGraph base = staging == null ? StagingGraph.empty() : staging;
        return switch (toolName) {
            case "add_blueprint_instance" -> addBlueprintInstance(args, base);
            case "wire_ports" -> wirePorts(args, base);
            case "set_params" -> setParams(args, base);
            case "remove_instance" -> removeInstance(args, base);
            case "remove_wire" -> removeWire(args, base);
            case "rename_instance" -> renameInstance(args, base);
            case "set_pipeline_setting" -> setPipelineSetting(args);
            default -> throw new MutationValidationException(
                    "Not a mutation tool: '" + toolName + "'");
        };
    }

    // ------------------------------------------------------------------
    // add_blueprint_instance -> AddInstances (NO initial params, 3-F4)
    // ------------------------------------------------------------------

    private PlanOperation addBlueprintInstance(Map<String, Object> args, StagingGraph base) {
        String ref = str(args, "instance_name");
        String blueprintKey = str(args, "blueprint_key");
        String reasoning = requireReasoning(args);
        if (ref == null || ref.isBlank()) {
            throw new MutationValidationException("add_blueprint_instance requires instance_name (the instance ref)");
        }
        if (blueprintKey == null || blueprintKey.isBlank()) {
            throw new MutationValidationException("add_blueprint_instance requires blueprint_key");
        }
        if (base.instance(ref) != null) {
            throw new MutationValidationException(
                    "Instance ref '" + ref + "' already exists in the staging graph");
        }

        Blueprint bp = blueprintRepo.findByBlueprintKey(blueprintKey)
                .orElseThrow(() -> new MutationValidationException(
                        "Unknown blueprint '" + blueprintKey + "' (not in the catalog)"));

        // Reject deprecated / deferred (ARCH-014, BLUEPRINT_COMPAT_READ_ONLY).
        if (compat.isCompatReadOnly(bp)) {
            String replacement = compat.replacementFor(blueprintKey);
            throw new MutationValidationException(
                    "BLUEPRINT_COMPAT_READ_ONLY: blueprint '" + blueprintKey
                            + "' is deprecated / deferred"
                            + (replacement != null && !replacement.isBlank()
                                    ? " (use '" + replacement + "' instead)" : "")
                            + " and cannot be added.");
        }

        // Reject orchestration-policy / non-composition surfaces (ARCH-011/012).
        String surface = bp.getAddSurface();
        if (surface != null && !"composition".equalsIgnoreCase(surface)) {
            if ("orchestration_policy".equalsIgnoreCase(surface)) {
                throw new MutationValidationException(
                        "STEP_REQUIRES_PIPELINE_ORCHESTRATION: '" + blueprintKey
                                + "' is an orchestration policy; configure it via set_pipeline_setting, "
                                + "not as a composition step.");
            }
            throw new MutationValidationException(
                    "Blueprint '" + blueprintKey + "' is not addable to composition (add_surface="
                            + surface + ").");
        }

        // Canonical storage fields (optional). NO initial params (3-F4).
        String storageBackend = str(args, "storage_backend");
        String lakeLayer = str(args, "lake_layer");
        String lakeFormat = str(args, "lake_format");

        return new PlanOperation.AddInstances(
                List.of(new PlanOperation.InstanceSpec(ref, blueprintKey, storageBackend, lakeLayer, lakeFormat)),
                reasoning);
    }

    // ------------------------------------------------------------------
    // wire_ports -> MergeWiring (additive, REQUIRED reasoning)
    // ------------------------------------------------------------------

    private PlanOperation wirePorts(Map<String, Object> args, StagingGraph base) {
        String sourceRef = firstNonBlank(str(args, "source_instance_name"), str(args, "source_ref"));
        String sourcePort = firstNonBlank(str(args, "source_port"), str(args, "source_port_name"));
        String targetRef = firstNonBlank(str(args, "target_instance_name"), str(args, "target_ref"));
        String targetPort = firstNonBlank(str(args, "target_port"), str(args, "target_port_name"));
        String reasoning = requireReasoning(args);

        if (sourceRef == null || targetRef == null || sourcePort == null || targetPort == null) {
            throw new MutationValidationException(
                    "wire_ports requires source_instance_name, source_port, target_instance_name, target_port");
        }
        requireRef(base, sourceRef, "wire_ports source");
        requireRef(base, targetRef, "wire_ports target");
        validatePort(base, sourceRef, sourcePort, /*outputPort=*/true);
        validatePort(base, targetRef, targetPort, /*outputPort=*/false);

        return new PlanOperation.MergeWiring(
                List.of(new PlanOperation.WireSpec(sourceRef, sourcePort, targetRef, targetPort)),
                reasoning);
    }

    // ------------------------------------------------------------------
    // set_params -> UpdateInstance (STRUCTURED, no sub-LLM)
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private PlanOperation setParams(Map<String, Object> args, StagingGraph base) {
        String ref = firstNonBlank(str(args, "instance_name"), str(args, "instance_ref"));
        if (ref == null || ref.isBlank()) {
            throw new MutationValidationException("set_params requires instance_name (the instance ref)");
        }
        requireRef(base, ref, "set_params");
        Object rawParams = args.get("params");
        Map<String, Object> params = rawParams instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String storageBackend = str(args, "storage_backend");
        String lakeLayer = str(args, "lake_layer");
        String lakeFormat = str(args, "lake_format");
        if (params.isEmpty() && storageBackend == null && lakeLayer == null && lakeFormat == null) {
            throw new MutationValidationException(
                    "set_params requires a non-empty params object or a storage/lake field");
        }
        return new PlanOperation.UpdateInstance(
                ref, new LinkedHashMap<>(params), storageBackend, lakeLayer, lakeFormat,
                strOr(args, "reasoning", ""));
    }

    // ------------------------------------------------------------------
    // remove_instance -> RemoveInstance
    // ------------------------------------------------------------------

    private PlanOperation removeInstance(Map<String, Object> args, StagingGraph base) {
        String ref = firstNonBlank(str(args, "instance_name"), str(args, "instance_ref"));
        if (ref == null || ref.isBlank()) {
            throw new MutationValidationException("remove_instance requires instance_name (the instance ref)");
        }
        requireRef(base, ref, "remove_instance");
        return new PlanOperation.RemoveInstance(ref, strOr(args, "reasoning", ""));
    }

    // ------------------------------------------------------------------
    // remove_wire -> RemoveWiring
    // ------------------------------------------------------------------

    private PlanOperation removeWire(Map<String, Object> args, StagingGraph base) {
        String sourceRef = firstNonBlank(str(args, "source_instance_name"), str(args, "source_ref"));
        String sourcePort = firstNonBlank(str(args, "source_port"), str(args, "source_port_name"));
        String targetRef = firstNonBlank(str(args, "target_instance_name"), str(args, "target_ref"));
        String targetPort = firstNonBlank(str(args, "target_port"), str(args, "target_port_name"));
        if (sourceRef == null || targetRef == null || sourcePort == null || targetPort == null) {
            throw new MutationValidationException(
                    "remove_wire requires source_instance_name, source_port, target_instance_name, target_port");
        }
        return new PlanOperation.RemoveWiring(
                new PlanOperation.WireSpec(sourceRef, sourcePort, targetRef, targetPort),
                strOr(args, "reasoning", ""));
    }

    // ------------------------------------------------------------------
    // rename_instance -> Rename
    // ------------------------------------------------------------------

    private PlanOperation renameInstance(Map<String, Object> args, StagingGraph base) {
        String oldRef = firstNonBlank(str(args, "instance_name"), str(args, "old_name"), str(args, "old_ref"));
        String newRef = firstNonBlank(str(args, "new_name"), str(args, "new_ref"));
        if (oldRef == null || newRef == null || oldRef.isBlank() || newRef.isBlank()) {
            throw new MutationValidationException(
                    "rename_instance requires instance_name (old ref) and new_name");
        }
        requireRef(base, oldRef, "rename_instance");
        if (base.instance(newRef) != null) {
            throw new MutationValidationException(
                    "rename_instance target ref '" + newRef + "' already exists");
        }
        return new PlanOperation.Rename(oldRef, newRef, strOr(args, "reasoning", ""));
    }

    // ------------------------------------------------------------------
    // set_pipeline_setting -> SetPipelineSetting
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private PlanOperation setPipelineSetting(Map<String, Object> args) {
        Object rawSettings = args.get("settings");
        Map<String, Object> settings = rawSettings instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        if (settings.isEmpty()) {
            throw new MutationValidationException(
                    "set_pipeline_setting requires a non-empty settings object");
        }
        return new PlanOperation.SetPipelineSetting(
                new LinkedHashMap<>(settings), strOr(args, "reasoning", ""));
    }

    // ------------------------------------------------------------------
    // Semantic helpers
    // ------------------------------------------------------------------

    private void requireRef(StagingGraph base, String ref, String ctx) {
        if (base.instance(ref) == null) {
            throw new MutationValidationException(
                    ctx + ": instance ref '" + ref + "' does not exist in the staging graph");
        }
    }

    /**
     * Port existence / type check against the blueprint's declared ports. Best
     * effort: if the blueprint or its port list is unknown, the apply-time
     * canonical validator ({@code CompositionService.wirePort}) is the backstop.
     */
    @SuppressWarnings("unchecked")
    private void validatePort(StagingGraph base, String ref, String port, boolean outputPort) {
        StagingGraph.StagingInstance inst = base.instance(ref);
        if (inst == null) return;
        Blueprint bp = blueprintRepo.findByBlueprintKey(inst.blueprintKey()).orElse(null);
        if (bp == null) return;
        List<Map<String, Object>> ports = outputPort ? bp.getOutputPorts() : bp.getInputPorts();
        if (ports == null || ports.isEmpty()) return; // dynamic/none — defer to apply-time check
        boolean found = false;
        for (Map<String, Object> p : ports) {
            Object name = p.get("name");
            if (name != null && name.toString().equals(port)) {
                found = true;
                break;
            }
        }
        // GenericRouter has dynamic output ports resolved at wire time; do not
        // hard-reject unknown output ports for it (apply-time validates).
        if (!found && !"GenericRouter".equals(inst.blueprintKey())) {
            throw new MutationValidationException(
                    "Invalid " + (outputPort ? "output" : "input") + " port '" + port
                            + "' on '" + ref + "' [" + inst.blueprintKey() + "]");
        }
    }

    private String requireReasoning(Map<String, Object> args) {
        String reasoning = str(args, "reasoning");
        if (reasoning == null || reasoning.isBlank()) {
            throw new MutationValidationException(
                    "reasoning is REQUIRED (the per-op rationale shown in the Plan Preview)");
        }
        return reasoning;
    }

    private static String str(Map<String, Object> args, String key) {
        Object v = args.get(key);
        return v == null ? null : v.toString();
    }

    private static String strOr(Map<String, Object> args, String key, String fallback) {
        String v = str(args, key);
        return v == null ? fallback : v;
    }

    private static String firstNonBlank(String... values) {
        if (values == null) return null;
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
