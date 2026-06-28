package com.pulse.command.controller;

import com.pulse.command.model.CommandLog;
import com.pulse.command.model.Plan;
import com.pulse.command.service.CommandService;
import com.pulse.command.service.PlanService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/plans")
public class PlanController {

    private final PlanService planService;
    private final CommandService commandService;

    public PlanController(PlanService planService, CommandService commandService) {
        this.planService = planService;
        this.commandService = commandService;
    }

    @GetMapping
    public ResponseEntity<List<Plan>> listPlans(
            @PathVariable String tenantId,
            @RequestParam(required = false) String pipelineId) {
        List<Plan> plans = (pipelineId != null)
                ? planService.listByPipeline(pipelineId)
                : planService.listByTenant(tenantId);
        return ResponseEntity.ok(plans);
    }

    @GetMapping("/{planId}")
    public ResponseEntity<Plan> getPlan(
            @PathVariable String tenantId,
            @PathVariable String planId) {
        return ResponseEntity.ok(planService.get(planId));
    }

    @GetMapping("/{planId}/commands")
    public ResponseEntity<List<CommandLog>> getPlanCommands(
            @PathVariable String tenantId,
            @PathVariable String planId) {
        return ResponseEntity.ok(commandService.listByPlan(planId));
    }

    /**
     * ARCH-009 approval. Body must reference a {@code USER} chat message in
     * the same session, which itself references the plan via {@code plan_id}.
     */
    @PostMapping("/{planId}/approve")
    public ResponseEntity<Plan> approvePlan(
            @PathVariable String tenantId,
            @PathVariable String planId,
            @RequestBody ApproveRequest request) {
        return ResponseEntity.ok(planService.approve(
                planId, request.approvingMessageId(), request.approvingUserId()));
    }

    /**
     * ARCH-009 apply. Only valid for APPROVED plans; reads commands from the
     * persisted {@code planned_commands} so the caller cannot substitute.
     */
    @PostMapping("/{planId}/apply")
    public ResponseEntity<Plan> applyPlan(
            @PathVariable String tenantId,
            @PathVariable String planId) {
        return ResponseEntity.ok(planService.apply(planId));
    }

    @PostMapping("/{planId}/cancel")
    public ResponseEntity<Plan> cancelPlan(
            @PathVariable String tenantId,
            @PathVariable String planId) {
        return ResponseEntity.ok(planService.cancel(planId));
    }

    public record ApproveRequest(
            String approvingMessageId,
            String approvingUserId) {}
}
