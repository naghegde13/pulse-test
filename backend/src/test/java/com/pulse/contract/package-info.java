/**
 * Layer 2.5 contract tests (PKT-CAND-2026-05-26-67 META-packet).
 *
 * <p>These tests are NOT Spring-Boot tests. They run as pure unit tests that
 * scan source files on disk and assert structural contracts between layers
 * the existing Suite A {@code MockMvc} integration tests cannot reach:
 *
 * <ul>
 *   <li>{@link com.pulse.contract.EndpointReferenceContractTest} — every
 *       endpoint path string that {@code ConsolidatedTenantReadinessService}
 *       hands operators as a remediation hint must resolve to a real
 *       {@code @RequestMapping} on some controller. Catches the BUG-58 /
 *       BUG-70 / BUG-71 shape (service ships, controller forgotten).</li>
 *   <li>{@link com.pulse.contract.UiActionRoleContractTest} — every
 *       {@code @PreAuthorize} expression in any
 *       {@code com.pulse.**.controller.*Controller} must reference a role
 *       that exists in {@link com.pulse.auth.policy.PulseRole}. Catches the
 *       BUG-66 shape (UI exposes Save → backend 403s because the role-string
 *       drifted).</li>
 *   <li>{@link com.pulse.contract.AdapterConfigVsFormFieldContractTest} —
 *       every config key the deploy adapter records read from
 *       {@code DeploymentTarget.getConfig()} must be present as an input on
 *       the corresponding Create form. Catches the BUG-69 shape (form
 *       under-specifies the target).</li>
 * </ul>
 *
 * <h2>Why source-scan, not Spring context?</h2>
 *
 * <p>Each contract test is a {@code @Tag("contract")} unit test that boots
 * in &lt; 100 ms by scanning files under {@code backend/src/main/java/} and
 * {@code frontend/src/}. This keeps the contract lane as a fast PR gate that
 * runs every push, where a full Spring context boot (Suite A) is reserved
 * for the integration lane.
 *
 * <h2>Expected-pass-after-merge state</h2>
 *
 * <p>Some tests in this package will FAIL today against {@code main} because
 * the upstream lane they guard has not yet shipped its fix. Such tests carry
 * an {@code // EXPECTED-PASS-AFTER: BUG-NN} comment naming the bug whose merge
 * turns the test green. They are NOT {@code @Disabled} — they fail loudly so
 * the gate-flip is visible in the next CI run after the fix lands. See
 * {@code DEVIATIONS.md} on the SU-8 branch for the per-test status table.
 */
package com.pulse.contract;
