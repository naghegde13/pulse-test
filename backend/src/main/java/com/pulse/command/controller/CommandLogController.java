package com.pulse.command.controller;

import com.pulse.command.model.CommandLog;
import com.pulse.command.service.CommandService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/commands")
public class CommandLogController {

    private final CommandService commandService;

    public CommandLogController(CommandService commandService) {
        this.commandService = commandService;
    }

    @GetMapping
    public ResponseEntity<List<CommandLog>> listCommands(
            @PathVariable String tenantId,
            @RequestParam(required = false) String aggregateId) {
        List<CommandLog> commands = (aggregateId != null)
                ? commandService.listByAggregate(aggregateId)
                : commandService.listByTenant(tenantId);
        return ResponseEntity.ok(commands);
    }
}
