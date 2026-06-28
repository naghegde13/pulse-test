package com.pulse.auth.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.deploy.environment.DeploymentEnvironment;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Centralized role × action × environment policy matrix test
 * (TASK_P0_authorization_policy_negative_matrix).
 *
 * <p>Locks every {@link PulseAction} × {@link PulseRole} ×
 * {@link DeploymentEnvironment} cell to the decision recorded in
 * {@code /auth/authorization-matrix.json}. Every cell marked
 * {@code "deny"} is asserted with an explicit deny reason so a code
 * change that silently flips a deny to allow fails the build.
 *
 * <p>Also enforces enum-vs-fixture parity: if a new {@link PulseAction},
 * {@link PulseRole}, or {@link DeploymentEnvironment} enum value lands
 * without a matrix row, this test fails with a message naming the
 * missing value.
 *
 * <p>Scope: pure unit test against {@link AuthorizationPolicyService}.
 * No Spring context, no Postgres, no Docker — belongs in the
 * {@code fastPrTest} lane (no {@code @Tag("integration")} or
 * {@code @Tag("runtime")}).
 *
 * <p>The matrix is pinned to current Phase 3 behavior. Cells that
 * "should" deny but currently allow (cross-environment elevated
 * privilege) are tracked under {@code followUpNotes} in the fixture
 * file; see also {@link #aspirationalSegregationOfDutiesIsTrackedInFixture()}.
 */
class AuthorizationPolicyMatrixTest {

    /** Path of the matrix evidence fixture, relative to test resources. */
    private static final String MATRIX_RESOURCE = "/auth/authorization-matrix.json";

    /** Tenant id used everywhere — caller and ActionContext share it so the
     * tenant-membership gate never fires for non-admin roles. Tenant
     * mismatch and platform-admin tenant bypass are covered by
     * {@code UserTenantRolePolicyTest}, not duplicated here. */
    private static final String TENANT = "tenant-A";

    private static AuthorizationPolicyService policy;
    private static JsonNode matrixRoot;
    private static List<MatrixCell> cells;

    @BeforeAll
    static void loadFixture() throws Exception {
        policy = new AuthorizationPolicyService();
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = AuthorizationPolicyMatrixTest.class.getResourceAsStream(MATRIX_RESOURCE)) {
            assertNotNull(in,
                    "authorization-matrix.json missing from test resources at " + MATRIX_RESOURCE);
            matrixRoot = mapper.readTree(in);
        }
        JsonNode cellsNode = matrixRoot.path("cells");
        assertTrue(cellsNode.isArray(),
                "authorization-matrix.json must contain a top-level 'cells' array");

        List<MatrixCell> parsed = new ArrayList<>(cellsNode.size());
        for (JsonNode cell : cellsNode) {
            String action = cell.path("action").asText(null);
            String role = cell.path("role").asText(null);
            String env = cell.path("environment").asText(null);
            String expected = cell.path("expected").asText(null);
            String reason = cell.hasNonNull("reason") ? cell.get("reason").asText() : null;
            assertNotNull(action, "cell.action is required");
            assertNotNull(role, "cell.role is required");
            assertNotNull(env, "cell.environment is required");
            assertNotNull(expected, "cell.expected is required");
            assertTrue(expected.equals("allow") || expected.equals("deny"),
                    "cell.expected must be 'allow' or 'deny' (got '" + expected
                            + "') for action=" + action + " role=" + role + " env=" + env);
            if (expected.equals("allow")) {
                assertNull(reason,
                        "allow cell must not have a deny reason (action=" + action
                                + " role=" + role + " env=" + env + ")");
            } else {
                assertNotNull(reason,
                        "deny cell must declare a stable reason (action=" + action
                                + " role=" + role + " env=" + env + ")");
            }
            parsed.add(new MatrixCell(action, role, env, expected, reason));
        }
        cells = List.copyOf(parsed);
    }

    // ------------------------------------------------------------------
    // TC_new_pulse_action_must_be_in_matrix and the structural parity
    // siblings: enum ↔ fixture coverage.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Every PulseAction enum value has at least one matrix row (TC_new_pulse_action_must_be_in_matrix)")
    void everyPulseActionHasMatrixRows() {
        Set<String> covered = cells.stream().map(c -> c.action).collect(Collectors.toSet());
        Set<String> enumActions = Arrays.stream(PulseAction.values())
                .map(PulseAction::matrixKey).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(enumActions);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "authorization-matrix.json is missing cells for PulseAction(s): " + missing
                        + " — add a row per (role, environment) for every new action before merging.");
        // And every action key in the fixture is a real enum value (catches typos).
        Set<String> stale = new LinkedHashSet<>(covered);
        stale.removeAll(enumActions);
        assertTrue(stale.isEmpty(),
                "authorization-matrix.json references unknown action(s): " + stale);
    }

    @Test
    @DisplayName("Every PulseRole enum value has at least one matrix row")
    void everyPulseRoleHasMatrixRows() {
        Set<String> covered = cells.stream().map(c -> c.role).collect(Collectors.toSet());
        Set<String> enumRoles = Arrays.stream(PulseRole.values())
                .map(PulseRole::matrixKey).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(enumRoles);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "authorization-matrix.json is missing cells for PulseRole(s): " + missing);
        Set<String> stale = new LinkedHashSet<>(covered);
        stale.removeAll(enumRoles);
        assertTrue(stale.isEmpty(),
                "authorization-matrix.json references unknown role(s): " + stale);
    }

    @Test
    @DisplayName("Every DeploymentEnvironment enum value has at least one matrix row")
    void everyDeploymentEnvironmentHasMatrixRows() {
        Set<String> covered = cells.stream().map(c -> c.environment).collect(Collectors.toSet());
        Set<String> enumEnvs = Arrays.stream(DeploymentEnvironment.values())
                .map(DeploymentEnvironment::key).collect(Collectors.toSet());
        Set<String> missing = new LinkedHashSet<>(enumEnvs);
        missing.removeAll(covered);
        assertTrue(missing.isEmpty(),
                "authorization-matrix.json is missing cells for DeploymentEnvironment(s): " + missing);
        Set<String> stale = new LinkedHashSet<>(covered);
        stale.removeAll(enumEnvs);
        assertTrue(stale.isEmpty(),
                "authorization-matrix.json references unknown environment(s): " + stale);
    }

    @Test
    @DisplayName("Matrix size = |PulseAction| × |PulseRole| × |DeploymentEnvironment|")
    void matrixCellCountMatchesCartesianProduct() {
        int expected = PulseAction.values().length
                * PulseRole.values().length
                * DeploymentEnvironment.values().length;
        assertEquals(expected, cells.size(),
                "matrix cell count must equal full cartesian product of action × role × environment");

        // And every (action, role, env) triple is unique.
        Set<String> triples = new HashSet<>();
        for (MatrixCell c : cells) {
            String key = c.action + "|" + c.role + "|" + c.environment;
            assertTrue(triples.add(key),
                    "duplicate matrix cell: action=" + c.action + " role=" + c.role + " env=" + c.environment);
        }
    }

    // ------------------------------------------------------------------
    // TC_policy_matrix_deny_cells and TC_policy_matrix_sample_allow_cells:
    // every cell drives the real policy service and the outcome must
    // match the fixture exactly.
    // ------------------------------------------------------------------

    static Stream<MatrixCell> allCells() {
        // BeforeAll runs before parameter providers in JUnit Jupiter; we
        // can rely on `cells` being populated by the time this is invoked.
        return cells.stream();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("allCells")
    @DisplayName("Every matrix cell resolves to its recorded allow/deny decision (UI surface)")
    void everyMatrixCellMatchesPolicy(MatrixCell cell) {
        PulseAction action = enumAction(cell.action);
        PulseRole role = enumRole(cell.role);
        // Use UI surface — fixture documents cellsCallerSurface = "ui".
        CallerContext caller = caller(role, CallerSurface.UI);
        ActionContext ctx = actionContextFor(action, cell.environment);
        PolicyDecision decision = policy.check(caller, action, ctx);

        if (cell.expected.equals("allow")) {
            assertTrue(decision.allowed(),
                    "expected ALLOW but got DENY (" + decision.denyReason() + ") for "
                            + cellLabel(cell));
            assertNull(decision.denyReason(),
                    "allow decision must not carry a deny reason for " + cellLabel(cell));
        } else {
            assertFalse(decision.allowed(),
                    "expected DENY but policy allowed for " + cellLabel(cell));
            assertEquals(cell.reason, decision.denyReason(),
                    "deny reason mismatch for " + cellLabel(cell));
        }
    }

    // ------------------------------------------------------------------
    // TC_admin_can_perform_any_action_assert_explicit — ADMIN roles
    // (tenant_admin + platform_admin) get explicit per-cell coverage
    // rather than an implicit "admin can do everything" assumption.
    // ------------------------------------------------------------------

    @TestFactory
    @DisplayName("Explicit ADMIN role cells (tenant_admin + platform_admin) per action × env")
    Stream<DynamicTest> explicitAdminRoleCells() {
        Set<PulseRole> admins = EnumSet.of(PulseRole.TENANT_ADMIN, PulseRole.PLATFORM_ADMIN);
        return cells.stream()
                .filter(c -> admins.contains(enumRole(c.role)))
                .map(c -> DynamicTest.dynamicTest(
                        "ADMIN cell: " + cellLabel(c),
                        () -> {
                            PulseAction action = enumAction(c.action);
                            PulseRole role = enumRole(c.role);
                            CallerContext caller = caller(role, CallerSurface.UI);
                            ActionContext ctx = actionContextFor(action, c.environment);
                            PolicyDecision decision = policy.check(caller, action, ctx);
                            if (c.expected.equals("allow")) {
                                assertTrue(decision.allowed(),
                                        "ADMIN cell must allow: " + cellLabel(c)
                                                + " (got deny " + decision.denyReason() + ")");
                            } else {
                                assertFalse(decision.allowed(),
                                        "ADMIN cell must deny: " + cellLabel(c));
                                assertEquals(c.reason, decision.denyReason(),
                                        "ADMIN cell deny reason mismatch for " + cellLabel(c));
                            }
                        }));
    }

    // ------------------------------------------------------------------
    // Caller-surface variations — UI and AGENT must produce identical
    // decisions; SYSTEM is its own contract (COMMIT-only, platform-admin
    // only) and we assert it explicitly.
    // ------------------------------------------------------------------

    @ParameterizedTest(name = "[{index}] UI == AGENT for {0}")
    @MethodSource("allCells")
    @DisplayName("UI and AGENT caller surfaces produce identical decisions for every cell")
    void uiAndAgentSurfacesAgreePerCell(MatrixCell cell) {
        PulseAction action = enumAction(cell.action);
        PulseRole role = enumRole(cell.role);
        ActionContext ctx = actionContextFor(action, cell.environment);
        PolicyDecision uiDecision = policy.check(caller(role, CallerSurface.UI), action, ctx);
        PolicyDecision agentDecision = policy.check(caller(role, CallerSurface.AGENT), action, ctx);
        assertEquals(uiDecision.allowed(), agentDecision.allowed(),
                "UI vs AGENT surface drift for " + cellLabel(cell));
        assertEquals(uiDecision.denyReason(), agentDecision.denyReason(),
                "UI vs AGENT deny-reason drift for " + cellLabel(cell));
    }

    @Test
    @DisplayName("SYSTEM caller surface: PLATFORM_ADMIN may COMMIT only; every other action denies")
    void systemSurfaceContractIsExplicit() {
        CallerContext platformSystem = new CallerContext(
                "system-user", "tenant-platform",
                Set.of(PulseRole.PLATFORM_ADMIN),
                CallerSurface.SYSTEM);

        // COMMIT — allowed for platform-admin SYSTEM surface (scaffold).
        PolicyDecision commit = policy.check(platformSystem, PulseAction.COMMIT,
                ActionContext.forTenant(TENANT));
        assertTrue(commit.allowed(),
                "SYSTEM/PLATFORM_ADMIN must be allowed to COMMIT (scaffold); got deny "
                        + commit.denyReason());

        // Every other action — denied with the system-surface code.
        for (PulseAction action : PulseAction.values()) {
            if (action == PulseAction.COMMIT) continue;
            ActionContext ctx = actionContextFor(action, "dev");
            PolicyDecision d = policy.check(platformSystem, action, ctx);
            assertFalse(d.allowed(),
                    "SYSTEM surface must deny " + action + " even for PLATFORM_ADMIN");
            assertEquals("system_surface_action_not_allowed", d.denyReason(),
                    "SYSTEM surface non-COMMIT denial must carry stable reason for " + action);
        }
    }

    @Test
    @DisplayName("SYSTEM caller surface: non-PLATFORM_ADMIN cannot COMMIT")
    void systemSurfaceCommitRequiresPlatformAdmin() {
        // Every non-platform-admin role on SYSTEM surface attempting COMMIT.
        for (PulseRole role : PulseRole.values()) {
            if (role == PulseRole.PLATFORM_ADMIN) continue;
            CallerContext caller = new CallerContext(
                    "system-" + role.name().toLowerCase(Locale.ROOT),
                    "tenant-platform", Set.of(role),
                    CallerSurface.SYSTEM);
            PolicyDecision d = policy.check(caller, PulseAction.COMMIT,
                    ActionContext.forTenant(TENANT));
            assertFalse(d.allowed(),
                    "SYSTEM surface COMMIT must deny for role=" + role);
            assertEquals("system_commit_requires_platform_admin", d.denyReason(),
                    "SYSTEM surface non-admin COMMIT denial must carry stable reason for role=" + role);
        }
    }

    // ------------------------------------------------------------------
    // TC_cross_env_elevated_privilege_denied — DEV-only operator vs PROD.
    //
    // Pinned to CURRENT behavior: Phase 3 default ENV_MATRIX gives
    // DEPLOYMENT_OPERATOR every canonical env, so a "DEV approver cannot
    // approve PROD deploy" deny is not reachable by env config alone.
    // The aspirational deny is tracked in followUpNotes in the fixture;
    // this test pins the current state so a future Phase 4 SoD config
    // change is a deliberate matrix update, not an accident.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Aspirational env-SoD deny is tracked under followUpNotes; current behavior is allow")
    void aspirationalSegregationOfDutiesIsTrackedInFixture() {
        JsonNode notes = matrixRoot.path("followUpNotes");
        assertTrue(notes.isArray() && notes.size() >= 1,
                "followUpNotes must be present to track aspirational deny cells");
        boolean found = false;
        for (JsonNode note : notes) {
            if ("aspirational_env_segregation_of_duties".equals(note.path("topic").asText(""))) {
                found = true;
                assertFalse(note.path("current").asText("").isBlank(),
                        "followUpNote.current must describe current Phase 3 behavior");
                assertFalse(note.path("expected_future").asText("").isBlank(),
                        "followUpNote.expected_future must describe target Phase 4 SoD behavior");
            }
        }
        assertTrue(found,
                "followUpNotes must include 'aspirational_env_segregation_of_duties' entry");

        // And assert the current behavior the note describes — Phase 3
        // DEPLOYMENT_OPERATOR with no narrowing config is allowed in DEV
        // *and* in PROD. When Phase 4 narrows this, the matrix cells flip
        // and this assertion flips with them.
        CallerContext devOperator = caller(PulseRole.DEPLOYMENT_OPERATOR, CallerSurface.UI);
        assertTrue(policy.check(devOperator, PulseAction.DEPLOY,
                        ActionContext.forTenantAndEnv(TENANT, "dev")).allowed(),
                "Phase 3 baseline: DEPLOYMENT_OPERATOR must be allowed to deploy DEV");
        assertTrue(policy.check(devOperator, PulseAction.DEPLOY,
                        ActionContext.forTenantAndEnv(TENANT, "prod")).allowed(),
                "Phase 3 baseline: DEPLOYMENT_OPERATOR is currently allowed to deploy PROD too "
                        + "(no per-env narrowing). When this flips to deny under Phase 4, update the "
                        + "matrix cells and this assertion together.");
    }

    @Test
    @DisplayName("Cross-env unknown env still denies with unknown_environment regardless of role")
    void unknownEnvDeniesForEnvScopedActions() {
        // Sanity: every role that *can* deploy gets a deny for an unknown
        // env value (env normalization happens AFTER the role gate, so
        // disallowed roles still report missing_role rather than
        // unknown_environment — this asserts the contract per role).
        for (PulseRole role : PulseRole.values()) {
            CallerContext caller = caller(role, CallerSurface.UI);
            PolicyDecision d = policy.check(caller, PulseAction.DEPLOY,
                    ActionContext.forTenantAndEnv(TENANT, "STAGING"));
            assertFalse(d.allowed(),
                    "DEPLOY with unknown env must deny for role=" + role);
            // Roles without DEPLOY permission short-circuit at the role gate.
            boolean canDeploy = Set.of(
                    PulseRole.DEPLOYMENT_OPERATOR,
                    PulseRole.TENANT_ADMIN,
                    PulseRole.PLATFORM_ADMIN).contains(role);
            String expectedReason = canDeploy ? "unknown_environment" : "missing_role";
            assertEquals(expectedReason, d.denyReason(),
                    "DEPLOY/unknown env deny reason mismatch for role=" + role);
        }
    }

    // ------------------------------------------------------------------
    // Spot-check: ALLOW sampling per role — keeps the suite from being
    // over-restrictive even if a sea of denies were to mask a genuine
    // bug. One ALLOW per role is enough; the parameterized test covers
    // the rest.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Representative ALLOW cells (one per role with any allow) resolve to ALLOW")
    void representativeAllowCellsPerRole() {
        Map<String, MatrixCell> firstAllowPerRole = new LinkedHashMap<>();
        for (MatrixCell c : cells) {
            if (c.expected.equals("allow")) {
                firstAllowPerRole.putIfAbsent(c.role, c);
            }
        }
        // tenant_user has no allow cells under current policy — every other
        // role does. Document that as part of the assertion.
        Set<String> rolesWithAllow = firstAllowPerRole.keySet();
        Set<String> rolesWithoutAllow = Arrays.stream(PulseRole.values())
                .map(PulseRole::matrixKey).collect(Collectors.toSet());
        rolesWithoutAllow.removeAll(rolesWithAllow);
        assertEquals(Set.of(PulseRole.TENANT_USER.matrixKey()), rolesWithoutAllow,
                "Only TENANT_USER is expected to have zero ALLOW cells under Phase 3; got " + rolesWithoutAllow);

        for (MatrixCell sample : firstAllowPerRole.values()) {
            PulseAction action = enumAction(sample.action);
            PulseRole role = enumRole(sample.role);
            ActionContext ctx = actionContextFor(action, sample.environment);
            assertTrue(policy.check(caller(role, CallerSurface.UI), action, ctx).allowed(),
                    "representative ALLOW cell failed for " + cellLabel(sample));
        }
    }

    // ------------------------------------------------------------------
    // Helpers.
    // ------------------------------------------------------------------

    private static CallerContext caller(PulseRole role, CallerSurface surface) {
        // platform_admin is the only role allowed to bypass tenant
        // membership; for every other role we keep caller.tenantId ==
        // ActionContext.tenantId so the tenant gate never trips.
        return new CallerContext(
                "user-" + role.name().toLowerCase(Locale.ROOT),
                TENANT, Set.of(role), surface);
    }

    private static ActionContext actionContextFor(PulseAction action, String env) {
        // ENV_SCOPED actions (DEPLOY, PROMOTE) take env; other actions
        // ignore env entirely. We pass env on every cell so the policy
        // sees a fully-formed ActionContext for parameterized tests.
        if (action == PulseAction.DEPLOY || action == PulseAction.PROMOTE) {
            return ActionContext.forTenantAndEnv(TENANT, env);
        }
        return ActionContext.forTenant(TENANT);
    }

    private static PulseAction enumAction(String key) {
        for (PulseAction a : PulseAction.values()) {
            if (a.matrixKey().equals(key)) return a;
        }
        throw new IllegalStateException("Unknown action key in matrix: " + key);
    }

    private static PulseRole enumRole(String key) {
        for (PulseRole r : PulseRole.values()) {
            if (r.matrixKey().equals(key)) return r;
        }
        throw new IllegalStateException("Unknown role key in matrix: " + key);
    }

    private static String cellLabel(MatrixCell c) {
        return "action=" + c.action + " role=" + c.role + " env=" + c.environment
                + " expected=" + c.expected + (c.reason == null ? "" : " reason=" + c.reason);
    }

    /** Parsed matrix cell — keeps parameterized test names readable. */
    record MatrixCell(String action, String role, String environment,
                      String expected, String reason) {
        @Override
        public String toString() {
            return action + "/" + role + "/" + environment + "=" + expected;
        }
    }
}
