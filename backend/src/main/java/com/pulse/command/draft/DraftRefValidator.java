package com.pulse.command.draft;

import com.pulse.command.service.PlanService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class DraftRefValidator {

    public List<DraftRefDeclaration> deriveDeclarationsAndValidate(List<PlanService.PlannedCommand> commands) {
        Map<String, DraftRefDeclaration> allDeclarations = new LinkedHashMap<>();
        Set<String> declaredEarlier = new LinkedHashSet<>();

        for (int index = 0; index < commands.size(); index++) {
            PlanService.PlannedCommand command = commands.get(index);
            Map<String, PlanService.CommandOutput> currentOutputs = new LinkedHashMap<>();
            for (PlanService.CommandOutput output : command.outputs()) {
                if (output == null) {
                    continue;
                }
                if (!"aggregateId".equals(output.source())) {
                    throw new DraftRefException(
                            PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                            "Unsupported draft output source '" + output.source() + "' on command index " + index);
                }
                DraftRef draftRef = requireDraftRef(output.draftRef(), "command output", index);
                String expectedKind = normalizeAggregateKind(command.aggregateType());
                if (expectedKind != null && !expectedKind.equals(draftRef.kind())) {
                    throw new DraftRefException(
                            PlanDraftRefErrorCodes.PLAN_DRAFT_REF_KIND_MISMATCH,
                            "Command index " + index + " declares " + draftRef.raw()
                                    + " for aggregate type '" + command.aggregateType() + "'");
                }
                if (allDeclarations.containsKey(draftRef.raw())) {
                    throw new DraftRefException(
                            PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                            "Draft ref '" + draftRef.raw() + "' is declared more than once");
                }
                currentOutputs.put(draftRef.raw(), output);
                allDeclarations.put(draftRef.raw(), new DraftRefDeclaration(
                        draftRef.raw(),
                        draftRef.kind(),
                        index,
                        command.description()));
            }

            validateCommandUses(command, index, declaredEarlier, currentOutputs.keySet());
            declaredEarlier.addAll(currentOutputs.keySet());
        }

        return new ArrayList<>(allDeclarations.values());
    }

    public void validatePersistedDeclarations(List<PlanService.PlannedCommand> commands,
                                             List<Map<String, Object>> persistedDeclarations) {
        List<DraftRefDeclaration> derived = deriveDeclarationsAndValidate(commands);
        List<DraftRefDeclaration> persisted = persistedDeclarations == null
                ? List.of()
                : persistedDeclarations.stream().map(DraftRefDeclaration::fromMap).toList();
        if (!derived.equals(persisted)) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Persisted draft ref declarations no longer match planned commands");
        }
    }

    private void validateCommandUses(PlanService.PlannedCommand command,
                                     int commandIndex,
                                     Set<String> declaredEarlier,
                                     Set<String> currentOutputRefs) {
        validateUse(command.aggregateId(), command.aggregateType(), "aggregateId", commandIndex,
                declaredEarlier, currentOutputRefs, true);
        validateObject(command.payload(), commandIndex, declaredEarlier, currentOutputRefs, "payload");
        validateObject(command.uiIntent(), commandIndex, declaredEarlier, currentOutputRefs, "uiIntent");
    }

    @SuppressWarnings("unchecked")
    private void validateObject(Object value,
                                int commandIndex,
                                Set<String> declaredEarlier,
                                Set<String> currentOutputRefs,
                                String path) {
        if (value instanceof Map<?, ?> mapValue) {
            for (var entry : mapValue.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    validateObject(entry.getValue(), commandIndex, declaredEarlier, currentOutputRefs,
                            path + "." + key);
                }
            }
            return;
        }
        if (value instanceof List<?> listValue) {
            for (int i = 0; i < listValue.size(); i++) {
                validateObject(listValue.get(i), commandIndex, declaredEarlier, currentOutputRefs,
                        path + "[" + i + "]");
            }
            return;
        }
        if (value instanceof String stringValue) {
            validateUse(stringValue, inferExpectedKind(path), path, commandIndex,
                    declaredEarlier, currentOutputRefs, false);
        }
    }

    private void validateUse(String candidate,
                             String expectedKindOrAggregateType,
                             String path,
                             int commandIndex,
                             Set<String> declaredEarlier,
                             Set<String> currentOutputRefs,
                             boolean aggregateIdPath) {
        var parsed = DraftRef.tryParse(candidate);
        if (parsed.isEmpty()) {
            return;
        }
        DraftRef draftRef = parsed.get();
        String expectedKind = normalizeAggregateKind(expectedKindOrAggregateType);
        if (expectedKind != null && !expectedKind.equals(draftRef.kind())) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_KIND_MISMATCH,
                    "Draft ref '" + draftRef.raw() + "' does not match expected kind '" + expectedKind
                            + "' at command index " + commandIndex + " path " + path);
        }
        if (currentOutputRefs.contains(draftRef.raw())) {
            if (aggregateIdPath || path.startsWith("uiIntent.")) {
                return;
            }
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                    "Command index " + commandIndex
                            + " cannot reference its own output draft ref '" + draftRef.raw()
                            + "' outside aggregateId or uiIntent");
        }
        if (!declaredEarlier.contains(draftRef.raw())) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_UNDECLARED,
                    "Draft ref '" + draftRef.raw() + "' is not declared before command index " + commandIndex);
        }
    }

    private DraftRef requireDraftRef(String rawDraftRef, String context, int commandIndex) {
        return DraftRef.tryParse(rawDraftRef)
                .orElseThrow(() -> new DraftRefException(
                        PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                        "Expected a draft ref in " + context + " on command index " + commandIndex));
    }

    private String inferExpectedKind(String path) {
        String leaf = path.substring(path.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (leaf) {
            case "pipelineid", "pipeline_id" -> "pipeline";
            case "connectorinstanceid", "connector_instance_id", "connectorid", "connector_id" -> "connector";
            default -> null;
        };
    }

    private String normalizeAggregateKind(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.replace("_", "").toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "pipeline" -> "pipeline";
            case "connector", "connectorinstance" -> "connector";
            default -> null;
        };
    }
}
