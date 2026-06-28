package com.pulse.command.service;

import com.pulse.auth.model.PulseUser;
import com.pulse.auth.model.Tenant;
import com.pulse.auth.repository.TenantRepository;
import com.pulse.auth.repository.UserRepository;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.command.model.CommandLog;
import com.pulse.command.model.CommandStatus;
import com.pulse.command.repository.CommandLogRepository;
import com.pulse.pipeline.repository.PipelineRepository;
import com.pulse.pipeline.repository.PipelineVersionRepository;
import com.pulse.pipeline.repository.PortWiringRepository;
import com.pulse.pipeline.repository.SubPipelineInstanceRepository;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import com.pulse.sor.repository.ConnectorInstanceRepository;
import com.pulse.sor.repository.DatasetRepository;
import com.pulse.sor.repository.DomainRepository;
import com.pulse.sor.repository.SystemOfRecordRepository;
import com.pulse.support.SeedFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Baseline coverage for {@link CommandService} — handler dispatch, success and failure
 * persistence, payload merge, listing ordering.
 *
 * <p><strong>Idempotency build-out gap.</strong> {@link CommandService#execute} currently
 * synthesises the idempotency key by appending a random {@code UUID}, which makes replay
 * deduplication impossible to test today. {@link #identicalRequests_currentlyProduceTwoRows_documentsRandomUuidIdempotencyKey}
 * is a regression test that codifies the current behaviour so the team is forced to revisit
 * it when deterministic keys land (tracked by TST-008). When that change ships, this test
 * will need to be updated to assert dedupe instead of two distinct rows.
 *
 * <p>Lives in the fast PR lane: pure H2 + create-drop, no integration tag, no IT suffix.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CommandServiceTest {

    @Autowired private CommandService commandService;
    @Autowired private CommandLogRepository commandLogRepository;

    // SeedFixtures wiring (mirrors SeedFixturesTest to avoid re-implementing seed code).
    @Autowired private TenantRepository tenantRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private DomainRepository domainRepository;
    @Autowired private SystemOfRecordRepository sorRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;
    @Autowired private ConnectorInstanceRepository connectorInstanceRepository;
    @Autowired private DatasetRepository datasetRepository;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private PipelineRepository pipelineRepository;
    @Autowired private PipelineVersionRepository pipelineVersionRepository;
    @Autowired private SubPipelineInstanceRepository subPipelineInstanceRepository;
    @Autowired private PortWiringRepository portWiringRepository;

    private SeedFixtures seedFixtures;
    private Tenant tenant;
    private PulseUser actor;

    @BeforeEach
    void seedIdentity() {
        seedFixtures = new SeedFixtures(
                tenantRepository, userRepository, domainRepository, sorRepository,
                connectorDefinitionRepository, connectorInstanceRepository, datasetRepository,
                blueprintRepository, pipelineRepository, pipelineVersionRepository,
                subPipelineInstanceRepository, portWiringRepository);
        tenant = seedFixtures.seedTenant();
        actor = seedFixtures.seedUser(tenant.getId());
    }

    // ---------------------------------------------------------------------
    //  TC_command_service_success_appends_log
    // ---------------------------------------------------------------------

    @Test
    void execute_successPath_appendsCommandLogWithSucceededStatusAndMergedPayload() {
        // Given — a registered handler returns a payload-shaped result
        String commandType = "TC.success." + SeedFixtures.nextSuffix();
        Map<String, Object> handlerResult = Map.of("created_id", "agg-42", "version", 7);
        commandService.registerHandler(commandType, cmd -> handlerResult);

        Map<String, Object> inputPayload = new LinkedHashMap<>();
        inputPayload.put("name", "loan_master");
        inputPayload.put("foo", "bar");

        // When
        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-1",
                tenant.getId(), actor.getId(), null, inputPayload);

        // Then — single row, SUCCEEDED, payload merged
        assertEquals(CommandStatus.SUCCEEDED, cmd.getStatus(), "status SUCCEEDED on success path");
        assertEquals(tenant.getId(), cmd.getTenantId(), "tenant id preserved");
        assertEquals(actor.getId(), cmd.getActorId(), "actor id preserved");
        assertEquals("Pipeline", cmd.getAggregateType(), "aggregate type preserved");
        assertEquals("pipeline-1", cmd.getAggregateId(), "aggregate id preserved");
        assertNotNull(cmd.getExecutedAt(), "executedAt set on terminal transition");
        assertNull(cmd.getErrorMessage(), "no error message on success");

        List<CommandLog> all = commandLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.getId());
        assertEquals(1, all.size(), "exactly one CommandLog row inserted");

        Map<String, Object> persistedPayload = all.get(0).getPayload();
        assertNotNull(persistedPayload, "payload persisted");
        assertEquals("loan_master", persistedPayload.get("name"), "original payload field preserved");
        assertEquals("bar", persistedPayload.get("foo"), "original payload field preserved");
        assertEquals(handlerResult, all.get(0).getResultPayload(),
                "structured map results must persist under resultPayload");
        assertNotNull(persistedPayload.get("_result"), "handler Map result merged under _result key");
        // Without this assertion any regression that drops the merge silently breaks the command log UI.
        @SuppressWarnings("unchecked")
        Map<String, Object> resultBlob = (Map<String, Object>) persistedPayload.get("_result");
        assertEquals("agg-42", resultBlob.get("created_id"), "handler result body merged into payload");
    }

    // ---------------------------------------------------------------------
    //  TC_command_service_failure_appends_log_failed
    // ---------------------------------------------------------------------

    @Test
    void execute_failurePath_appendsCommandLogWithFailedStatusAndCapturedErrorMessage() {
        // Given — handler throws a RuntimeException
        String commandType = "TC.failure." + SeedFixtures.nextSuffix();
        commandService.registerHandler(commandType, cmd -> { throw new RuntimeException("boom"); });

        // When
        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-2",
                tenant.getId(), actor.getId(), null, Map.of());

        // Then — FAILED + thrown message captured + no silent retry
        assertEquals(CommandStatus.FAILED, cmd.getStatus(), "status FAILED on handler exception");
        assertEquals("boom", cmd.getErrorMessage(), "thrown message captured verbatim");
        assertNotNull(cmd.getExecutedAt(), "executedAt set on terminal transition");

        List<CommandLog> all = commandLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.getId());
        assertEquals(1, all.size(), "exactly one CommandLog row — no silent retry");
    }

    @Test
    void execute_handlerThrowsNullPointerException_persistsFailedRowWithSameShape() {
        // Negative case from the packet: unchecked exception types (NPE, IllegalArgument)
        // should produce the same row shape as a RuntimeException.
        String commandType = "TC.npe." + SeedFixtures.nextSuffix();
        commandService.registerHandler(commandType, cmd -> { throw new NullPointerException("null bang"); });

        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-npe",
                tenant.getId(), actor.getId(), null, Map.of());

        assertEquals(CommandStatus.FAILED, cmd.getStatus(), "NPE still produces FAILED");
        assertEquals("null bang", cmd.getErrorMessage(), "NPE message captured verbatim");
        assertNotNull(cmd.getExecutedAt(), "executedAt set on terminal transition");
    }

    // ---------------------------------------------------------------------
    //  Negative case: unregistered command type produces FAILED audit row
    //  rather than throwing, so the audit trail is preserved.
    // ---------------------------------------------------------------------

    @Test
    void execute_unregisteredCommandType_persistsFailedRowExplainingNoHandler() {
        // No handler registered.
        String commandType = "TC.unregistered." + SeedFixtures.nextSuffix();

        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-3",
                tenant.getId(), actor.getId(), null, Map.of());

        assertEquals(CommandStatus.FAILED, cmd.getStatus(),
                "unregistered handler results in FAILED row, not thrown exception (audit preserved)");
        assertNotNull(cmd.getErrorMessage(), "error message explains why");
        assertTrue(cmd.getErrorMessage().contains("No handler registered"),
                "message identifies the missing-handler case");
        assertTrue(cmd.getErrorMessage().contains(commandType),
                "message identifies which command type was missing");
    }

    // ---------------------------------------------------------------------
    //  Payload result merge — non-Map result is recorded by type name
    // ---------------------------------------------------------------------

    @Test
    void execute_handlerReturnsNonMapResult_storesResultTypeUnderPayloadKey() {
        String commandType = "TC.scalar." + SeedFixtures.nextSuffix();
        commandService.registerHandler(commandType, cmd -> "just-a-string");

        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-scalar",
                tenant.getId(), actor.getId(), null, new LinkedHashMap<>(Map.of("k", "v")));

        assertEquals(CommandStatus.SUCCEEDED, cmd.getStatus());
        Map<String, Object> payload = cmd.getPayload();
        assertEquals("v", payload.get("k"), "original payload preserved");
        assertEquals("String", payload.get("_resultType"),
                "non-Map result captured as type name under _resultType");
        assertNull(cmd.getResultPayload(), "non-Map handler results do not populate structured resultPayload");
    }

    @Test
    void execute_handlerReturnsNull_keepsPayloadAndStillMarksSucceeded() {
        // CommandService skips the merge when the handler returns null.
        String commandType = "TC.null." + SeedFixtures.nextSuffix();
        commandService.registerHandler(commandType, cmd -> null);

        Map<String, Object> input = new LinkedHashMap<>(Map.of("name", "only_this"));
        CommandLog cmd = commandService.execute(
                commandType, "Pipeline", "pipeline-null",
                tenant.getId(), actor.getId(), null, input);

        assertEquals(CommandStatus.SUCCEEDED, cmd.getStatus());
        Map<String, Object> payload = cmd.getPayload();
        assertEquals("only_this", payload.get("name"), "payload preserved untouched");
        assertNull(payload.get("_result"), "no _result merged when handler returns null");
        assertNull(payload.get("_resultType"), "no _resultType when handler returns null");
        assertNull(cmd.getResultPayload(), "null handler results leave resultPayload unset");
    }

    // ---------------------------------------------------------------------
    //  Listing behaviour
    // ---------------------------------------------------------------------

    @Test
    void listByTenant_returnsOnlyCallingTenantRowsOrderedByCreatedAtDesc() {
        // Given — two tenants, two commands each
        Tenant otherTenant = seedFixtures.seedTenant();
        PulseUser otherActor = seedFixtures.seedUser(otherTenant.getId());

        String typeA = "TC.list.a." + SeedFixtures.nextSuffix();
        commandService.registerHandler(typeA, cmd -> Map.of("ok", true));

        CommandLog t1c1 = commandService.execute(typeA, "Pipeline", "p-1",
                tenant.getId(), actor.getId(), null, Map.of());
        CommandLog t1c2 = commandService.execute(typeA, "Pipeline", "p-2",
                tenant.getId(), actor.getId(), null, Map.of());
        CommandLog t2c1 = commandService.execute(typeA, "Pipeline", "p-99",
                otherTenant.getId(), otherActor.getId(), null, Map.of());

        // When
        List<CommandLog> tenant1Rows = commandService.listByTenant(tenant.getId());
        List<CommandLog> tenant2Rows = commandService.listByTenant(otherTenant.getId());

        // Then — no cross-tenant leak
        assertEquals(2, tenant1Rows.size(), "tenant 1 sees exactly its 2 rows");
        assertEquals(1, tenant2Rows.size(), "tenant 2 sees exactly its 1 row");
        for (CommandLog row : tenant1Rows) {
            assertEquals(tenant.getId(), row.getTenantId(), "no cross-tenant leak");
        }
        // ordered by createdAt desc — newest first
        assertEquals(t1c2.getId(), tenant1Rows.get(0).getId(), "newest row first");
        assertEquals(t1c1.getId(), tenant1Rows.get(1).getId(), "oldest row last");
        assertEquals(t2c1.getId(), tenant2Rows.get(0).getId(), "tenant 2 only sees its own row");
    }

    @Test
    void listByAggregate_returnsAllRowsForSingleAggregateOrderedDesc() {
        String type = "TC.aggregate." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        CommandLog first = commandService.execute(type, "Pipeline", "shared-aggregate",
                tenant.getId(), actor.getId(), null, Map.of());
        CommandLog second = commandService.execute(type, "Pipeline", "shared-aggregate",
                tenant.getId(), actor.getId(), null, Map.of());
        CommandLog otherAggregate = commandService.execute(type, "Pipeline", "other-aggregate",
                tenant.getId(), actor.getId(), null, Map.of());

        List<CommandLog> rows = commandService.listByAggregate("shared-aggregate");
        assertEquals(2, rows.size(), "only commands for the requested aggregate");
        assertEquals(second.getId(), rows.get(0).getId(), "newest first");
        assertEquals(first.getId(), rows.get(1).getId(), "oldest last");
        assertNotEquals(otherAggregate.getId(), rows.get(0).getId());
        assertNotEquals(otherAggregate.getId(), rows.get(1).getId());
    }

    @Test
    void listByPlan_returnsRowsForGivenPlanOrderedByCreatedAtAsc() {
        String type = "TC.plan." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        String planId = "plan-" + SeedFixtures.nextSuffix();
        CommandLog first = commandService.execute(type, "Pipeline", "agg-1",
                tenant.getId(), actor.getId(), planId, Map.of());
        CommandLog second = commandService.execute(type, "Pipeline", "agg-2",
                tenant.getId(), actor.getId(), planId, Map.of());

        List<CommandLog> rows = commandService.listByPlan(planId);
        assertEquals(2, rows.size());
        // Ascending — chat plan UI walks commands in execution order.
        assertEquals(first.getId(), rows.get(0).getId(), "oldest first for plan ordering");
        assertEquals(second.getId(), rows.get(1).getId(), "newest last for plan ordering");
    }

    // ---------------------------------------------------------------------
    //  Edge case: concurrent (sequential, same-thread) handle calls for the
    //  same command type produce two separate rows. CommandService has no
    //  in-flight dedupe.
    // ---------------------------------------------------------------------

    @Test
    void execute_twoCallsForSameCommandType_produceTwoSeparateRows_noIncidentalDedupe() {
        String type = "TC.no-dedupe." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        CommandLog a = commandService.execute(type, "Pipeline", "same-aggregate",
                tenant.getId(), actor.getId(), null, Map.of());
        CommandLog b = commandService.execute(type, "Pipeline", "same-aggregate",
                tenant.getId(), actor.getId(), null, Map.of());

        assertNotEquals(a.getId(), b.getId(), "two rows, two ids");
        assertNotEquals(a.getIdempotencyKey(), b.getIdempotencyKey(),
                "idempotency keys differ because they include a random UUID today");
    }

    // ---------------------------------------------------------------------
    //  TC_random_uuid_idempotency_key_documented
    //
    //  This regression test documents the current behaviour: identical command
    //  requests still produce two distinct CommandLog rows because the
    //  idempotency key embeds a fresh UUID per call. When deterministic
    //  idempotency keys land (TST-008), this test must be updated to assert
    //  dedupe instead of two rows — that change is build-out-first and
    //  out of scope for this baseline task.
    // ---------------------------------------------------------------------

    @Test
    void identicalRequests_currentlyProduceTwoRows_documentsRandomUuidIdempotencyKey() {
        String type = "TC.idempotency-doc." + SeedFixtures.nextSuffix();
        commandService.registerHandler(type, cmd -> Map.of());

        // Two byte-for-byte identical execute() calls.
        Map<String, Object> payload = Map.of("k", "v");
        CommandLog first = commandService.execute(type, "Pipeline", "same-id",
                tenant.getId(), actor.getId(), null, payload);
        CommandLog second = commandService.execute(type, "Pipeline", "same-id",
                tenant.getId(), actor.getId(), null, payload);

        // Until deterministic keys ship, "replay-safety" cannot be tested — both
        // calls succeed and create independent audit rows.
        assertNotEquals(first.getId(), second.getId(),
                "TST-008: two distinct rows confirm replay-safety is build-out-first");
        assertNotEquals(first.getIdempotencyKey(), second.getIdempotencyKey(),
                "idempotency keys today are random per call — when deterministic keys land "
                        + "this assertion must be flipped to assertEquals to drive the dedupe path");
        long total = commandLogRepository.findByTenantIdOrderByCreatedAtDesc(tenant.getId()).stream()
                .filter(row -> row.getCommandType().equals(type))
                .count();
        assertEquals(2L, total,
                "two identical requests produce two CommandLog rows under the current key strategy");
    }
}
