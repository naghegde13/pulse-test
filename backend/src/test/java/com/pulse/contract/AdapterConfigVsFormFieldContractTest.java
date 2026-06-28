package com.pulse.contract;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Layer 2.5 contract test (BUG-2026-05-26-67 META-packet, BUG-69 regression
 * guard).
 *
 * <p>BUG-69 was "Create Deployment Target form is under-specified for
 * {@code GCP_COMPOSER_DATAPROC} — the form only collects {@code name /
 * environment / targetType / endpointUrl} but the adapter's
 * {@link com.pulse.deploy.adapter.gcp.GcpComposerDataprocAdapter.GcpTargetConfig}
 * record reads ten distinct keys ({@code gcpProject}, {@code gcsBucket},
 * {@code composerEnvironment}, {@code dataprocRegion}, {@code dataprocCluster},
 * {@code mainPyFile}, {@code dagFilePaths}, {@code pythonFiles},
 * {@code jarFiles}, {@code tokenReference}) from {@code target.getConfig()}."
 *
 * <p>The DPC adapter has the same shape (ten keys, all of which the form
 * does not collect). The form's silent reliance on hardcoded fixture
 * defaults means a real deploy target row never carries the operator's
 * intended values; the operator cannot see this gap because the Create form
 * exits successfully.
 *
 * <p>This test enumerates every {@code c.getOrDefault("key", …)} call in
 * each adapter's nested {@code *TargetConfig} record (the only static
 * surface that names the keys the adapter consumes) and compares against the
 * keys present in the corresponding Create form. Any key the adapter reads
 * but the form does not surface is reported as a BUG-69-class regression.
 *
 * <h3>EXPECTED-PASS-AFTER status</h3>
 *
 * <p>This test FAILS today against {@code main} with a precise list of
 * missing form fields per adapter type. It goes green when BUG-69 ships
 * (the Create form grows the per-target-type config field collection).
 *
 * <p>The test is intentionally NOT {@code @Disabled} — its red status IS
 * the BUG-69 regression signal.
 */
@Tag("contract")
@DisplayName("Layer 2.5 / BUG-67 — every deploy adapter config key must be collected by the corresponding Create form")
class AdapterConfigVsFormFieldContractTest {

    private static final Path BACKEND_SRC_MAIN = Paths.get("src", "main", "java");

    /**
     * Frontend source root, resolved relative to the backend's working dir.
     * Gradle runs the test JVM with {@code working-dir = backend/} so we
     * walk up one level to reach {@code frontend/}.
     */
    private static final Path FRONTEND_SRC = Paths.get("..", "frontend", "src");

    /**
     * Adapter → form mapping. Each entry says "this adapter file
     * (containing a {@code record FooTargetConfig}) corresponds to this
     * form file (containing a Create draft for the same {@code targetType})."
     *
     * <p>The local-materialization adapter is intentionally excluded — it
     * has no configurable keys (it's a synthetic "always-succeeds" adapter
     * for dev/local mode), so there's nothing to collect on the form.
     */
    private static final List<AdapterFormPair> ADAPTER_FORM_PAIRS = List.of(
            new AdapterFormPair(
                    "GCP_COMPOSER_DATAPROC",
                    BACKEND_SRC_MAIN.resolve("com/pulse/deploy/adapter/gcp/GcpComposerDataprocAdapter.java"),
                    FRONTEND_SRC.resolve("components/settings/deployment-targets-panel.tsx")
            ),
            new AdapterFormPair(
                    "DPC_AIRFLOW_OPENSHIFT_SPARK",
                    BACKEND_SRC_MAIN.resolve("com/pulse/deploy/adapter/dpc/DpcAirflowOpenShiftSparkAdapter.java"),
                    FRONTEND_SRC.resolve("components/settings/deployment-targets-panel.tsx")
            )
    );

    /**
     * Form-side keys that legitimately exist OUTSIDE per-targetType config —
     * they are always-present fields on the top-level
     * {@code DeploymentTarget} row (not nested inside {@code config}). The
     * adapter does not read them through {@code c.getOrDefault}; they live
     * on the row's typed columns. Including them in the form's "collected"
     * set lets the assertion focus on actual per-targetType gaps.
     */
    private static final Set<String> ALWAYS_PRESENT_FORM_FIELDS = Set.of(
            "name", "environment", "targetType", "endpointUrl"
    );

    /**
     * Captures {@code c.getOrDefault("someKey", …)} calls (the canonical way
     * each adapter pulls a config value out of the JSONB map). Group(1) =
     * the key name.
     */
    private static final Pattern CONFIG_KEY_READ = Pattern.compile(
            "c\\.getOrDefault\\(\\s*\"([A-Za-z_][A-Za-z0-9_]*)\""
    );

    /**
     * Captures TSX form-field {@code name: "someKey"} / {@code key: "someKey"}
     * declarations inside a Create draft object literal. We also accept
     * {@code "someKey": ...} as the key-side of a JSON-style request body
     * to forgive minor stylistic variation. Group(1) = key name.
     */
    private static final Pattern FORM_FIELD_KEY = Pattern.compile(
            "[\"'`]([A-Za-z_][A-Za-z0-9_]*)[\"'`]\\s*:"
    );

    @Test
    @DisplayName("every adapter config key the backend reads is collected by the matching Create form")
    void everyAdapterConfigKeyIsCollectedByForm() throws IOException {
        Map<String, List<String>> gapsPerTargetType = new LinkedHashMap<>();

        for (AdapterFormPair pair : ADAPTER_FORM_PAIRS) {
            Set<String> adapterKeys = readAdapterConfigKeys(pair.adapterFile);
            Set<String> formKeys = readFormFieldKeys(pair.formFile);
            // Form-collected keys = explicit body keys + always-present columns.
            Set<String> collected = new LinkedHashSet<>(formKeys);
            collected.addAll(ALWAYS_PRESENT_FORM_FIELDS);

            List<String> missing = new ArrayList<>();
            for (String adapterKey : adapterKeys) {
                if (!collected.contains(adapterKey)) {
                    missing.add(adapterKey);
                }
            }
            if (!missing.isEmpty()) {
                gapsPerTargetType.put(pair.targetType, missing);
            }
        }

        assertThat(gapsPerTargetType)
                .as("Every config key any deploy adapter's *TargetConfig record reads via "
                        + "c.getOrDefault(\"key\", …) must be collectable on the matching Create form. "
                        + "Missing keys per targetType are listed below — each one is a BUG-69-class "
                        + "regression where the form silently relies on the adapter's hardcoded "
                        + "fixture defaults and never carries the operator's intended values to a "
                        + "real deploy. Fix by extending the Create form's per-targetType field set; "
                        + "or, if a key is intentionally derived elsewhere (e.g. from tenant GCP "
                        + "config), move the c.getOrDefault read out of the adapter and into a "
                        + "service so the contract narrows to keys the form actually owns.")
                .isEmpty();
    }

    // ---------------------------------------------------------------------
    //  Source-scan helpers
    // ---------------------------------------------------------------------

    private static Set<String> readAdapterConfigKeys(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new AssertionError("Adapter source missing: " + file.toAbsolutePath()
                    + " — the contract test cannot verify this targetType. Did the file move?");
        }
        Set<String> keys = new LinkedHashSet<>();
        String body = Files.readString(file);
        Matcher m = CONFIG_KEY_READ.matcher(body);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static Set<String> readFormFieldKeys(Path file) throws IOException {
        if (!Files.exists(file)) {
            throw new AssertionError("Form source missing: " + file.toAbsolutePath()
                    + " — the contract test cannot verify this targetType. Did the file move?");
        }
        Set<String> keys = new LinkedHashSet<>();
        String body = Files.readString(file);
        Matcher m = FORM_FIELD_KEY.matcher(body);
        while (m.find()) {
            String k = m.group(1);
            // Exclude reserved TS/React object-literal keys that show up as
            // false positives from the broad "...\":" pattern.
            if (RESERVED_OBJECT_KEYS.contains(k)) continue;
            keys.add(k);
        }
        return keys;
    }

    /**
     * Common object-literal keys that appear in TSX but aren't form fields:
     * style/className/key (React reserved), state-machine status enums,
     * etc. Pre-filtering these keeps the form-side key set focused on
     * actual request-body / draft fields.
     */
    private static final Set<String> RESERVED_OBJECT_KEYS = Set.of(
            "style", "className", "key", "children", "ref", "id", "type",
            "default", "value", "label", "placeholder", "disabled", "required",
            "checked", "readOnly", "onClick", "onChange", "onSubmit", "onBlur",
            "onFocus", "onKeyDown", "onKeyUp", "onMouseEnter", "onMouseLeave",
            "href", "target", "rel", "src", "alt", "title", "role", "tabIndex",
            "aria-label", "data-testid", "data-readiness-blocker"
    );

    private record AdapterFormPair(String targetType, Path adapterFile, Path formFile) {}
}
