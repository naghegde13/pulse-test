package com.pulse.command;

import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.model.Plan;
import com.pulse.command.model.PlanStatus;
import com.pulse.command.repository.CommandLogRepository;
import com.pulse.command.repository.PlanRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class V133PlanDraftRefAliasingMigrationTest {

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlanRepository planRepository;
    @Autowired private CommandLogRepository commandLogRepository;

    @Test
    void plansTable_hasDraftRefColumns() {
        assertColumnExists("plans", "draft_ref_declarations");
        assertColumnExists("plans", "draft_ref_bindings");
    }

    @Test
    void commandLogTable_hasResultPayloadColumn() {
        assertColumnExists("command_log", "result_payload");
    }

    @Test
    void planAndCommandLog_roundTripDraftAndResultJson() {
        Plan plan = new Plan();
        plan.setTenantId("tenant-v133");
        plan.setActorId("actor-v133");
        plan.setDescription("draft ref round trip");
        plan.setStatus(PlanStatus.PREVIEW);
        plan.setCommandIds(List.of());
        plan.setDraftRefDeclarations(List.of(Map.of(
                "draftRef", "draft:pipeline:1",
                "aggregateType", "pipeline",
                "declaredByCommandIndex", 0
        )));
        plan.setDraftRefBindings(List.of(Map.of(
                "draftRef", "draft:pipeline:1",
                "aggregateType", "pipeline",
                "realId", "pipeline-real-1",
                "boundByCommandIndex", 0,
                "boundByCommandId", "cmd-1",
                "boundAt", Instant.parse("2026-05-14T12:00:00Z").toString()
        )));
        plan = planRepository.save(plan);

        CommandLog log = new CommandLog();
        log.setPlanId(plan.getId());
        log.setCommandType("pipeline.create");
        log.setAggregateType("Pipeline");
        log.setAggregateId("pipeline-real-1");
        log.setTenantId("tenant-v133");
        log.setActorId("actor-v133");
        log.setIdempotencyKey("idempotency-v133");
        log.setPayload(Map.of("name", "Loan Master"));
        log.setResultPayload(Map.of(
                "createdAggregateType", "pipeline",
                "createdAggregateId", "pipeline-real-1"
        ));
        log.setStatus(CommandStatus.SUCCEEDED);
        log.setExecutedAt(Instant.now());
        commandLogRepository.save(log);

        Plan reloadedPlan = planRepository.findById(plan.getId()).orElseThrow();
        CommandLog reloadedLog = commandLogRepository.findById(log.getId()).orElseThrow();

        assertEquals("draft:pipeline:1",
                reloadedPlan.getDraftRefDeclarations().get(0).get("draftRef"));
        assertEquals("pipeline-real-1",
                reloadedPlan.getDraftRefBindings().get(0).get("realId"));
        assertNotNull(reloadedLog.getResultPayload());
        assertEquals("pipeline-real-1", reloadedLog.getResultPayload().get("createdAggregateId"));
    }

    private void assertColumnExists(String table, String column) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS " +
                        "WHERE UPPER(TABLE_NAME)=UPPER(?) AND UPPER(COLUMN_NAME)=UPPER(?)",
                Integer.class, table, column);
        assertEquals(1, count, table + "." + column + " must exist after V133");
    }
}
