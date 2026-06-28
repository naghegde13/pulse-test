package com.pulse.command.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.exception.BlueprintCompatReadOnlyException;
import com.pulse.blueprint.service.DeprecatedBlueprintCompatibilityService;
import com.pulse.chat.repository.ChatMessageRepository;
import com.pulse.command.draft.DraftRef;
import com.pulse.command.draft.DraftRefBinding;
import com.pulse.command.draft.DraftRefDeclaration;
import com.pulse.command.draft.DraftRefException;
import com.pulse.command.draft.DraftRefSubstitutor;
import com.pulse.command.draft.DraftRefValidator;
import com.pulse.command.draft.PlanDraftRefErrorCodes;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.repository.CommandLogRepository;
import com.pulse.command.repository.PlanRepository;
import com.pulse.common.exception.ResourceNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Plan lifecycle: PREVIEW -> APPROVED -> APPLYING -> APPLIED, with FAILED and
 * CANCELLED as terminal states. ARCH-009 introduces the approval provenance
 * required for chat plans:
 *
 * <ul>
 *   <li>{@code sessionId} pins the plan to a chat session.</li>
 *   <li>{@code approvedByMessageId} requires the approval to come from a
 *       {@code USER} chat message in the same session.</li>
 *   <li>{@code plannedCommands} stores the executable command objects so
 *       {@link #apply(String, String, String)} can reconstruct them deterministically.</li>
 * </ul>
 */
@Service
public class PlanService {

    private final PlanRepository planRepository;
    private final CommandLogRepository commandLogRepository;
    private final CommandService commandService;
    private final ChatMessageRepository chatMessageRepository;
    private final DeprecatedBlueprintCompatibilityService compat;
    private final DraftRefValidator draftRefValidator = new DraftRefValidator();
    private final DraftRefSubstitutor draftRefSubstitutor = new DraftRefSubstitutor();
    private final TransactionTemplate commandStepTransaction;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PlanService(PlanRepository planRepository,
                       CommandLogRepository commandLogRepository,
                       CommandService commandService,
                       ChatMessageRepository chatMessageRepository,
                       DeprecatedBlueprintCompatibilityService compat,
                       PlatformTransactionManager transactionManager) {
        this.planRepository = planRepository;
        this.commandLogRepository = commandLogRepository;
        this.commandService = commandService;
        this.chatMessageRepository = chatMessageRepository;
        this.compat = compat;
        this.commandStepTransaction = new TransactionTemplate(transactionManager);
        this.commandStepTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public Plan get(String planId) {
        return planRepository.findById(planId)
                .orElseThrow(() -> new ResourceNotFoundException("Plan", planId));
    }

    public List<Plan> listByPipeline(String pipelineId) {
        return planRepository.findByPipelineIdOrderByCreatedAtDesc(pipelineId);
    }

    public List<Plan> listByTenant(String tenantId) {
        return planRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    /**
     * Backward-compatible preview helper for non-chat callers (e.g.
     * {@code PipelineService.create} which previews and applies in one go).
     * Does not require a session and does not persist {@code plannedCommands}
     * because the caller passes the full command list directly to
     * {@link #apply(String, String, String, List)}.
     */
    @Transactional
    public Plan createPreview(String tenantId, String pipelineId, String actorId,
                              String description, List<PlannedCommand> commands) {
        return createInternal(tenantId, pipelineId, /*sessionId=*/null,
                actorId, description, commands, /*persistCommands=*/false);
    }

    /**
     * ARCH-009: chat-side preview. Persists the full command objects under
     * {@code plannedCommands} so {@link #apply(String)} can reconstruct them
     * without trusting the caller. Returns a PREVIEW-status plan.
     */
    @Transactional
    public Plan createForSession(String tenantId, String pipelineId, String sessionId,
                                 String actorId, String description,
                                 List<PlannedCommand> commands) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId is required for chat plans (ARCH-009)");
        }
        return createInternal(tenantId, pipelineId, sessionId,
                actorId, description, commands, /*persistCommands=*/true);
    }

    private Plan createInternal(String tenantId, String pipelineId, String sessionId,
                                String actorId, String description,
                                List<PlannedCommand> commands,
                                boolean persistCommands) {
        List<PlannedCommand> normalizedCommands = normalizeCommands(commands);
        List<DraftRefDeclaration> declarations =
                draftRefValidator.deriveDeclarationsAndValidate(normalizedCommands);

        Plan plan = new Plan();
        plan.setTenantId(tenantId);
        plan.setPipelineId(pipelineId);
        plan.setSessionId(sessionId);
        plan.setActorId(actorId);
        plan.setDescription(description);
        plan.setStatus(PlanStatus.PREVIEW);
        plan.setCommandIds(new ArrayList<>());
        plan.setDraftRefDeclarations(declarations.stream().map(DraftRefDeclaration::toMap).toList());
        plan.setDraftRefBindings(new ArrayList<>());

        // Preview is for human display: command type, aggregate, description.
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("commandCount", normalizedCommands.size());
        preview.put("commands", normalizedCommands.stream().map(c -> Map.of(
                "type", c.commandType(),
                "aggregateType", c.aggregateType(),
                "description", c.description()
        )).toList());
        preview.put("draftRefDeclarations", declarations.stream().map(DraftRefDeclaration::toMap).toList());
        preview.put("draftRefBindingCount", 0);
        plan.setPreviewData(preview);

        if (persistCommands) {
            plan.setPlannedCommands(serializeCommands(normalizedCommands));
        }
        return planRepository.save(plan);
    }

    /**
     * ARCH-009: approve a chat plan. Requires the approving message to be a
     * {@code USER} chat message in the same session that references the plan
     * via {@code plan_id}.
     */
    @Transactional
    public Plan approve(String planId, String approvingMessageId, String approvingUserId) {
        Plan plan = get(planId);
        if (plan.getStatus() != PlanStatus.PREVIEW) {
            throw new IllegalArgumentException(
                    "Plan must be in PREVIEW to approve. Current: " + plan.getStatus());
        }
        if (plan.getSessionId() == null || plan.getSessionId().isBlank()) {
            throw new IllegalArgumentException(
                    "Plan has no sessionId; only chat plans can be approved (ARCH-009)");
        }
        if (approvingMessageId == null || approvingMessageId.isBlank()) {
            throw new IllegalArgumentException("approvedByMessageId is required");
        }

        var msg = chatMessageRepository.findById(approvingMessageId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "ChatMessage", approvingMessageId));
        if (!plan.getSessionId().equals(msg.getSessionId())) {
            throw new IllegalArgumentException(
                    "Approving message belongs to a different session than the plan");
        }
        if (!"USER".equals(msg.getRole())) {
            throw new IllegalArgumentException(
                    "Only USER chat messages may approve a plan (role was " + msg.getRole() + ")");
        }
        if (!planId.equals(msg.getPlanId())) {
            throw new IllegalArgumentException(
                    "Approving message does not reference this plan (plan_id mismatch)");
        }

        plan.setStatus(PlanStatus.APPROVED);
        plan.setApprovedAt(Instant.now());
        plan.setApprovedByMessageId(approvingMessageId);
        plan.setApprovedByUserId(approvingUserId);
        return planRepository.save(plan);
    }

    /**
     * Apply an APPROVED chat plan. Reads commands from
     * {@code plannedCommands} so the caller cannot substitute alternative
     * command objects.
     *
     * @param planId plan to apply.
     * @return the applied plan.
     */
    public Plan apply(String planId) {
        Plan plan = get(planId);
        if (plan.getStatus() == PlanStatus.FAILED && !safeMapList(plan.getDraftRefBindings()).isEmpty()) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_NON_RESUMABLE,
                    "Failed plan '" + planId + "' already persisted draft ref bindings and cannot be resumed");
        }
        if (plan.getStatus() != PlanStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Plan must be in APPROVED to apply via apply_plan. Current: "
                            + plan.getStatus());
        }
        if (plan.getPlannedCommands() == null || plan.getPlannedCommands().isEmpty()) {
            throw new IllegalArgumentException(
                    "Plan has no plannedCommands; cannot reconstruct command list");
        }
        List<PlannedCommand> commands = restoreDeclaredOutputs(
                deserializeCommands(plan.getPlannedCommands()),
                safeMapList(plan.getDraftRefDeclarations()));

        return executeCommands(plan, plan.getActorId(), commands, true);
    }

    /**
     * Walk every command in an APPROVED plan and reject the apply if any of
     * them touches a blueprint or instance that is now deprecated / deferred
     * (ARCH-014, ARCH-018). The first hit aborts the apply before any
     * mutation runs; the plan is left in APPROVED so the user can either
     * cancel or re-plan.
     */
    private void revalidateCompatibility(List<PlannedCommand> commands) {
        for (PlannedCommand pc : commands) {
            if (pc.payload() == null) continue;
            Object bpKey = pc.payload().get("blueprintKey");
            if (bpKey == null) bpKey = pc.payload().get("blueprint_key");
            if (bpKey instanceof String key && !key.isBlank()) {
                if (compat.isCompatReadOnly(key)) {
                    throw new BlueprintCompatReadOnlyException(
                            key, compat.replacementFor(key));
                }
            }
            Object instanceId = pc.payload().get("instanceId");
            if (instanceId == null) instanceId = pc.payload().get("instance_id");
            if (instanceId instanceof String id && !id.isBlank()) {
                // rejectIfAnyInstanceCompatReadOnly resolves the instance and
                // checks its persisted blueprint key against the live catalog.
                compat.rejectIfAnyInstanceCompatReadOnly(java.util.List.of(id));
            }
        }
    }

    /**
     * Backward-compatible apply for non-chat callers that already hold the
     * command list (e.g. {@code PipelineService.create}'s single-shot
     * preview+apply). Caller is responsible for passing the same commands
     * supplied to {@link #createPreview(String, String, String, String, List)}.
     */
    @Transactional
    public Plan apply(String planId, String tenantId, String actorId,
                      List<PlannedCommand> commands) {
        Plan plan = get(planId);
        if (plan.getStatus() != PlanStatus.PREVIEW) {
            throw new IllegalArgumentException(
                    "Plan must be in PREVIEW to apply via legacy path. Current: "
                            + plan.getStatus());
        }
        return executeCommands(plan, actorId, commands, false);
    }

    private Plan executeCommands(Plan plan, String actorId, List<PlannedCommand> commands, boolean isolateSteps) {
        List<PlannedCommand> normalizedCommands = normalizeCommands(commands);
        draftRefValidator.validatePersistedDeclarations(normalizedCommands, safeMapList(plan.getDraftRefDeclarations()));

        Map<String, DraftRefBinding> bindings = loadPersistedBindings(plan);
        boolean applyStarted = false;

        for (int index = 0; index < normalizedCommands.size(); index++) {
            PlannedCommand original = normalizedCommands.get(index);
            PlannedCommand resolved = draftRefSubstitutor.substitute(
                    original,
                    bindings,
                    allowsOwnAggregateDraftRef(original));

            // ARCH-018 compatibility revalidation: use the latest resolved
            // payload, not the preview snapshot.
            revalidateCompatibility(List.of(resolved));

            if (!applyStarted) {
                markPlanApplying(plan.getId(), isolateSteps);
                applyStarted = true;
            }

            StepExecutionResult step = executeCommandStep(
                    plan.getId(), plan.getTenantId(), actorId, index, original, resolved, bindings, isolateSteps);
            if (!step.bindings().isEmpty()) {
                step.bindings().forEach(binding -> bindings.put(binding.draftRef(), binding));
            }
            if (step.failed()) {
                return get(plan.getId());
            }
        }

        return markPlanApplied(plan.getId(), isolateSteps);
    }

    @SuppressWarnings("unchecked")
    private List<PlannedCommand> deserializeCommands(List<Map<String, Object>> stored) {
        List<PlannedCommand> out = new ArrayList<>(stored.size());
        for (Map<String, Object> e : stored) {
            Map<String, Object> payloadMap = coerceMap(e.get("payload"));
            List<CommandOutput> outputs = deserializeOutputs(e.get("outputs"));
            Map<String, Object> uiIntent = coerceMap(e.get("uiIntent"));
            out.add(new PlannedCommand(
                    (String) e.get("type"),
                    (String) e.get("aggregateType"),
                    (String) e.get("aggregateId"),
                    (String) e.get("description"),
                    payloadMap,
                    outputs,
                    uiIntent));
        }
        return out;
    }

    /**
     * Session-scoped approve for the Plan-Preview decision endpoint (ADR 0025 /
     * IMPL-ui-composition Phase 4 — the {@code interruptBefore}-resume analogue).
     * The decision call IS the approval: it validates the plan belongs to
     * {@code sessionId} and is in PREVIEW, then promotes it to APPROVED. Unlike
     * {@link #approve(String, String, String)} it does not require a separate
     * approving USER chat message — the session-scoped decision transport is the
     * approval provenance.
     */
    @Transactional
    public Plan approveForSession(String planId, String sessionId, String approvingUserId) {
        Plan plan = get(planId);
        if (plan.getStatus() != PlanStatus.PREVIEW) {
            throw new IllegalArgumentException(
                    "Plan must be in PREVIEW to approve. Current: " + plan.getStatus());
        }
        if (plan.getSessionId() == null || !plan.getSessionId().equals(sessionId)) {
            throw new IllegalArgumentException(
                    "Plan does not belong to this chat session (cross-session decision rejected)");
        }
        plan.setStatus(PlanStatus.APPROVED);
        plan.setApprovedAt(Instant.now());
        plan.setApprovedByUserId(approvingUserId);
        return planRepository.save(plan);
    }

    @Transactional
    public Plan cancel(String planId) {
        Plan plan = get(planId);
        if (plan.getStatus() != PlanStatus.PREVIEW && plan.getStatus() != PlanStatus.APPROVED) {
            throw new IllegalArgumentException(
                    "Only PREVIEW or APPROVED plans can be cancelled (current: "
                            + plan.getStatus() + ")");
        }
        plan.setStatus(PlanStatus.CANCELLED);
        return planRepository.save(plan);
    }

    private List<Map<String, Object>> serializeCommands(List<PlannedCommand> commands) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (PlannedCommand pc : commands) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("type", pc.commandType());
            entry.put("aggregateType", pc.aggregateType());
            entry.put("aggregateId", pc.aggregateId());
            entry.put("description", pc.description());
            entry.put("payload", new LinkedHashMap<>(pc.payload()));
            if (!pc.outputs().isEmpty()) {
                entry.put("outputs", pc.outputs().stream().map(CommandOutput::toMap).toList());
            }
            if (!pc.uiIntent().isEmpty()) {
                entry.put("uiIntent", new LinkedHashMap<>(pc.uiIntent()));
            }
            serialized.add(entry);
        }
        return serialized;
    }

    private Map<String, DraftRefBinding> loadPersistedBindings(Plan plan) {
        List<Map<String, Object>> bindingRows = safeMapList(plan.getDraftRefBindings());
        Set<String> declaredRefs = new LinkedHashSet<>();
        for (Map<String, Object> row : safeMapList(plan.getDraftRefDeclarations())) {
            declaredRefs.add(DraftRefDeclaration.fromMap(row).draftRef());
        }

        Map<String, DraftRefBinding> bindings = new LinkedHashMap<>();
        for (Map<String, Object> row : bindingRows) {
            DraftRefBinding binding = DraftRefBinding.fromMap(row);
            if (!declaredRefs.contains(binding.draftRef())) {
                throw new DraftRefException(
                        PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                        "Draft ref binding '" + binding.draftRef() + "' is not declared on the plan");
            }
            DraftRefBinding existing = bindings.putIfAbsent(binding.draftRef(), binding);
            if (existing != null) {
                throw new DraftRefException(
                        PlanDraftRefErrorCodes.PLAN_DRAFT_REF_BINDING_CONFLICT,
                        "Draft ref '" + binding.draftRef() + "' is already bound");
            }
        }
        return bindings;
    }

    private Plan markPlanApplying(String planId, boolean isolateStep) {
        return inStepTransaction(isolateStep, status -> {
            Plan current = get(planId);
            current.setStatus(PlanStatus.APPLYING);
            return planRepository.save(current);
        });
    }

    private Plan markPlanApplied(String planId, boolean isolateStep) {
        return inStepTransaction(isolateStep, status -> {
            Plan current = get(planId);
            current.setStatus(PlanStatus.APPLIED);
            current.setAppliedAt(Instant.now());
            return planRepository.save(current);
        });
    }

    private StepExecutionResult executeCommandStep(String planId,
                                                   String tenantId,
                                                   String actorId,
                                                   int commandIndex,
                                                   PlannedCommand original,
                                                   PlannedCommand resolved,
                                                   Map<String, DraftRefBinding> existingBindings,
                                                   boolean isolateStep) {
        return inStepTransaction(isolateStep, status -> {
            Plan current = get(planId);
            CommandLog cmd = commandService.execute(
                    resolved.commandType(),
                    resolved.aggregateType(),
                    resolved.aggregateId(),
                    tenantId,
                    actorId,
                    planId,
                    resolved.payload());

            List<String> commandIds = new ArrayList<>(safeStrings(current.getCommandIds()));
            commandIds.add(cmd.getId());
            current.setCommandIds(commandIds);

            List<DraftRefBinding> newBindings = List.of();
            if (cmd.getStatus() != CommandStatus.FAILED) {
                try {
                    newBindings = deriveBindings(original, commandIndex, cmd, existingBindings);
                    if (!newBindings.isEmpty()) {
                        current.setDraftRefBindings(appendBindings(current.getDraftRefBindings(), newBindings));
                    }
                    appendResolvedUiIntent(current, original, existingBindings, newBindings);
                    String finalAggregateId = resolveFinalAggregateId(original, resolved, newBindings);
                    if (!finalAggregateId.equals(cmd.getAggregateId())) {
                        cmd.setAggregateId(finalAggregateId);
                    }
                } catch (DraftRefException ex) {
                    cmd.setStatus(CommandStatus.FAILED);
                    cmd.setErrorMessage(ex.getMessage());
                    cmd.setExecutedAt(Instant.now());
                    newBindings = List.of();
                }
            }

            commandLogRepository.save(cmd);
            boolean failed = cmd.getStatus() == CommandStatus.FAILED;
            if (failed) {
                current.setStatus(PlanStatus.FAILED);
                current.setAppliedAt(Instant.now());
            }
            planRepository.save(current);
            return new StepExecutionResult(cmd.getId(), failed, newBindings);
        });
    }

    private <T> T inStepTransaction(boolean isolateStep, Function<TransactionStatus, T> action) {
        if (isolateStep) {
            return commandStepTransaction.execute(action::apply);
        }
        return action.apply(null);
    }

    private void appendResolvedUiIntent(Plan current,
                                        PlannedCommand original,
                                        Map<String, DraftRefBinding> existingBindings,
                                        List<DraftRefBinding> newBindings) {
        if (original.uiIntent() == null || original.uiIntent().isEmpty()) {
            return;
        }
        Map<String, DraftRefBinding> allBindings = new LinkedHashMap<>(existingBindings);
        for (DraftRefBinding binding : newBindings) {
            allBindings.put(binding.draftRef(), binding);
        }
        Map<String, Object> resolvedIntent =
                draftRefSubstitutor.substituteMap(original.uiIntent(), allBindings, false);
        if (resolvedIntent.isEmpty()) {
            return;
        }

        Map<String, Object> preview = new LinkedHashMap<>(
                current.getPreviewData() == null ? Map.of() : current.getPreviewData());
        List<Map<String, Object>> intents = new ArrayList<>(safeMapListFromObject(preview.get("appliedUiIntents")));
        intents.add(resolvedIntent);
        preview.put("appliedUiIntents", intents);
        current.setPreviewData(preview);
    }

    private List<DraftRefBinding> deriveBindings(PlannedCommand original,
                                                 int commandIndex,
                                                 CommandLog cmd,
                                                 Map<String, DraftRefBinding> existingBindings) {
        if (original.outputs().isEmpty()) {
            return List.of();
        }

        Map<String, Object> resultPayload = cmd.getResultPayload();
        String createdAggregateId = resultPayload == null ? null : stringValue(resultPayload.get("createdAggregateId"));
        if (createdAggregateId == null || createdAggregateId.isBlank()) {
            throw new DraftRefException(
                    PlanDraftRefErrorCodes.PLAN_DRAFT_REF_RESULT_MISSING,
                    "Command " + cmd.getCommandType()
                            + " did not return resultPayload.createdAggregateId for draft binding");
        }

        String createdAggregateType = resultPayload == null ? null : stringValue(resultPayload.get("createdAggregateType"));
        List<DraftRefBinding> bindings = new ArrayList<>();
        for (CommandOutput output : original.outputs()) {
            DraftRef draftRef = DraftRef.tryParse(output.draftRef())
                    .orElseThrow(() -> new DraftRefException(
                            PlanDraftRefErrorCodes.PLAN_DRAFT_REF_INVALID,
                            "Command output '" + output.draftRef() + "' is not a draft ref"));
            if (existingBindings.containsKey(draftRef.raw())) {
                throw new DraftRefException(
                        PlanDraftRefErrorCodes.PLAN_DRAFT_REF_BINDING_CONFLICT,
                        "Draft ref '" + draftRef.raw() + "' is already bound");
            }
            if (createdAggregateType != null && !createdAggregateType.isBlank()
                    && !draftRef.kind().equals(createdAggregateType.toLowerCase())) {
                throw new DraftRefException(
                        PlanDraftRefErrorCodes.PLAN_DRAFT_REF_KIND_MISMATCH,
                        "Command result aggregate type '" + createdAggregateType
                                + "' does not match draft ref '" + draftRef.raw() + "'");
            }
            bindings.add(new DraftRefBinding(
                    draftRef.raw(),
                    draftRef.kind(),
                    createdAggregateId,
                    commandIndex,
                    cmd.getId(),
                    Instant.now()));
        }
        return bindings;
    }

    private String resolveFinalAggregateId(PlannedCommand original,
                                           PlannedCommand resolved,
                                           List<DraftRefBinding> newBindings) {
        var originalDraft = DraftRef.tryParse(original.aggregateId());
        if (originalDraft.isEmpty()) {
            return resolved.aggregateId();
        }
        for (DraftRefBinding binding : newBindings) {
            if (binding.draftRef().equals(originalDraft.get().raw())) {
                return binding.realId();
            }
        }
        return resolved.aggregateId();
    }

    private boolean allowsOwnAggregateDraftRef(PlannedCommand command) {
        var draftRef = DraftRef.tryParse(command.aggregateId());
        if (draftRef.isEmpty()) {
            return false;
        }
        return command.outputs().stream()
                .anyMatch(output -> "aggregateId".equals(output.source())
                        && draftRef.get().raw().equals(output.draftRef()));
    }

    private List<PlannedCommand> normalizeCommands(List<PlannedCommand> commands) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        return commands.stream().map(PlannedCommand::normalized).toList();
    }

    private List<PlannedCommand> restoreDeclaredOutputs(List<PlannedCommand> commands,
                                                        List<Map<String, Object>> declarations) {
        if (commands == null || commands.isEmpty() || declarations == null || declarations.isEmpty()) {
            return commands == null ? List.of() : commands;
        }
        List<PlannedCommand> restored = new ArrayList<>(commands);
        for (Map<String, Object> row : declarations) {
            DraftRefDeclaration declaration = DraftRefDeclaration.fromMap(row);
            int commandIndex = declaration.declaredByCommandIndex();
            if (commandIndex < 0 || commandIndex >= restored.size()) {
                continue;
            }
            PlannedCommand command = restored.get(commandIndex);
            boolean alreadyDeclared = command.outputs().stream()
                    .anyMatch(output -> declaration.draftRef().equals(output.draftRef()));
            if (alreadyDeclared) {
                continue;
            }
            List<CommandOutput> outputs = new ArrayList<>(command.outputs());
            outputs.add(new CommandOutput(declaration.draftRef(), "aggregateId"));
            restored.set(commandIndex, new PlannedCommand(
                    command.commandType(),
                    command.aggregateType(),
                    command.aggregateId(),
                    command.description(),
                    command.payload(),
                    outputs,
                    command.uiIntent()));
        }
        return restored;
    }

    private List<Map<String, Object>> appendBindings(List<Map<String, Object>> existingRows,
                                                     List<DraftRefBinding> newBindings) {
        List<Map<String, Object>> rows = new ArrayList<>(safeMapList(existingRows));
        rows.addAll(newBindings.stream().map(DraftRefBinding::toMap).toList());
        return rows;
    }

    private Map<String, Object> coerceMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    out.put(key, entry.getValue());
                }
            }
            return out;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                return objectMapper.readValue(text, new TypeReference<LinkedHashMap<String, Object>>() {});
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        if (raw == null) {
            return Map.of();
        }
        // The jsonb column can deserialize nested objects into a NON-java.util.Map
        // map representation depending on the active JSON FormatMapper (observed on
        // H2's native JSON round-trip: a scala.collection.immutable.Map). Re-encode
        // to a JSON string via that object's own toString-independent path and
        // re-parse, so persisted plannedCommands payloads survive apply (otherwise
        // they silently drop to an empty map and every composition.* command sees
        // null fields). Reflectively bridge Scala maps to a java.util.Map.
        Map<String, Object> bridged = reflectiveMapBridge(raw);
        if (bridged != null) {
            return bridged;
        }
        try {
            return objectMapper.convertValue(raw, new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    /**
     * Bridge a non-{@code java.util.Map} map representation (notably a
     * {@code scala.collection.Map}, which the H2 JSON FormatMapper produces for
     * nested jsonb objects) into a {@code java.util.LinkedHashMap} via its
     * {@code iterator()} of {@code Tuple2}s — all by reflection, so there is no
     * compile-time Scala dependency. Returns null when {@code raw} is not a
     * recognizable Scala/iterable map, so the caller falls back to Jackson.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> reflectiveMapBridge(Object raw) {
        if (!raw.getClass().getName().startsWith("scala.")) {
            return null;
        }
        try {
            Object iterator = raw.getClass().getMethod("iterator").invoke(raw);
            java.lang.reflect.Method hasNext = iterator.getClass().getMethod("hasNext");
            java.lang.reflect.Method next = iterator.getClass().getMethod("next");
            Map<String, Object> out = new LinkedHashMap<>();
            while ((Boolean) hasNext.invoke(iterator)) {
                Object tuple = next.invoke(iterator);
                Object k = tuple.getClass().getMethod("_1").invoke(tuple);
                Object v = tuple.getClass().getMethod("_2").invoke(tuple);
                Object value = (v != null && v.getClass().getName().startsWith("scala."))
                        ? bridgeScalaValue(v) : v;
                out.put(String.valueOf(k), value);
            }
            return out;
        } catch (Exception ignored) {
            return null;
        }
    }

    /** Recursively bridge a Scala nested value (Map or Seq) to its java.util equivalent. */
    @SuppressWarnings("unchecked")
    private Object bridgeScalaValue(Object v) {
        Map<String, Object> asMap = reflectiveMapBridge(v);
        if (asMap != null) {
            return asMap;
        }
        // Scala Seq/List -> java.util.List via iterator.
        try {
            Object iterator = v.getClass().getMethod("iterator").invoke(v);
            java.lang.reflect.Method hasNext = iterator.getClass().getMethod("hasNext");
            java.lang.reflect.Method next = iterator.getClass().getMethod("next");
            List<Object> list = new ArrayList<>();
            while ((Boolean) hasNext.invoke(iterator)) {
                Object e = next.invoke(iterator);
                list.add(e != null && e.getClass().getName().startsWith("scala.") ? bridgeScalaValue(e) : e);
            }
            return list;
        } catch (Exception ignored) {
            return v;
        }
    }

    private List<CommandOutput> deserializeOutputs(Object rawOutputs) {
        if (rawOutputs instanceof String text && !text.isBlank()) {
            try {
                rawOutputs = objectMapper.readValue(text, new TypeReference<List<Map<String, Object>>>() {});
            } catch (Exception ignored) {
                return List.of();
            }
        }
        if (!(rawOutputs instanceof List<?> outputList)) {
            return List.of();
        }
        List<CommandOutput> outputs = new ArrayList<>();
        for (Object output : outputList) {
            if (output instanceof Map<?, ?> mapOutput) {
                Object rawDraftRef = mapOutput.get("draftRef");
                Object rawSource = mapOutput.get("source");
                if (rawDraftRef instanceof String draftRef && rawSource instanceof String source) {
                    outputs.add(new CommandOutput(draftRef, source));
                }
            }
        }
        return outputs;
    }

    private List<Map<String, Object>> safeMapList(List<Map<String, Object>> rows) {
        return rows == null ? List.of() : rows;
    }

    private List<Map<String, Object>> safeMapListFromObject(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (var entry : map.entrySet()) {
                    if (entry.getKey() instanceof String key) {
                        row.put(key, entry.getValue());
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    private List<String> safeStrings(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String stringValue(Object value) {
        return value instanceof String text ? text : null;
    }

    public record PlannedCommand(
            String commandType,
            String aggregateType,
            String aggregateId,
            String description,
            Map<String, Object> payload,
            List<CommandOutput> outputs,
            Map<String, Object> uiIntent) {

        public PlannedCommand(String commandType,
                              String aggregateType,
                              String aggregateId,
                              String description,
                              Map<String, Object> payload) {
            this(commandType, aggregateType, aggregateId, description, payload, List.of(), Map.of());
        }

        public PlannedCommand normalized() {
            return new PlannedCommand(
                    commandType,
                    aggregateType,
                    aggregateId,
                    description,
                    payload == null ? Map.of() : new LinkedHashMap<>(payload),
                    outputs == null ? List.of() : List.copyOf(outputs),
                    uiIntent == null ? Map.of() : new LinkedHashMap<>(uiIntent));
        }

        public PlannedCommand withResolvedValues(String resolvedAggregateId,
                                                Map<String, Object> resolvedPayload,
                                                Map<String, Object> resolvedUiIntent) {
            return new PlannedCommand(
                    commandType,
                    aggregateType,
                    resolvedAggregateId,
                    description,
                    resolvedPayload,
                    outputs,
                    resolvedUiIntent);
        }
    }

    public record CommandOutput(String draftRef, String source) {
        public Map<String, Object> toMap() {
            return Map.of("draftRef", draftRef, "source", source);
        }
    }

    private record StepExecutionResult(
            String commandId,
            boolean failed,
            List<DraftRefBinding> bindings) {}
}
