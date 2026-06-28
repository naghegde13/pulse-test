package com.pulse.codegen;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Generates inline Great Expectations (GX) validation code for PySpark jobs.
 * <p>
 * Follows Option 1 from the GX Integration Proposal: ephemeral GX context
 * running inside the PySpark script with Spark execution engine.
 * <p>
 * Generated code creates an ephemeral GX context, registers a Spark data source,
 * builds an ExpectationSuite from configured expectations, runs a Checkpoint,
 * and branches on pass (continue) or fail (quarantine / fail / warn).
 */
@Component
public class GxCodeGenerator {

    /**
     * Generates Python code for GX data quality validation of a Spark DataFrame.
     *
     * @param variableName  The Python variable name of the Spark DataFrame to validate (e.g., "df")
     * @param expectations  List of expectation configs, each a map with keys:
     *                      "type" (String) - GX expectation class name (e.g., "ExpectColumnValuesToNotBeNull"),
     *                      "kwargs" (Map) - expectation parameters (e.g., {"column": "id", "mostly": 0.95}),
     *                      "severity" (String, optional) - "critical", "warning", or "info"
     * @param onFailure     Failure behavior: "fail", "warn", or "quarantine"
     * @return Generated Python code block as a string
     */
    public String generateGxValidation(String variableName, List<Map<String, Object>> expectations, String onFailure) {
        if (expectations == null || expectations.isEmpty()) {
            return "# --- GX Data Quality Validation skipped: no expectations configured ---\n";
        }

        String safeName = sanitize(variableName);
        String suiteName = safeName + "_dq_suite";
        String assetName = safeName + "_asset";
        String batchDefName = safeName + "_batch";
        String valDefName = safeName + "_validation";
        String checkpointName = safeName + "_checkpoint";

        StringBuilder py = new StringBuilder();

        // Section header
        py.append("\n# ").append("=".repeat(70)).append("\n");
        py.append("# GX Data Quality Validation for '").append(safeName).append("'\n");
        py.append("# ").append("=".repeat(70)).append("\n\n");

        // Imports
        py.append("import great_expectations as gx\n");
        py.append("from great_expectations.checkpoint import UpdateDataDocsAction\n\n");

        // Ephemeral context
        py.append("# Create ephemeral GX context (no filesystem required)\n");
        py.append("gx_context = gx.get_context(mode=\"ephemeral\")\n\n");

        // Spark data source
        py.append("# Register Spark execution engine\n");
        py.append("gx_ds = gx_context.data_sources.add_spark(name=\"pulse_spark\")\n");
        py.append("gx_asset = gx_ds.add_dataframe_asset(name=\"").append(assetName).append("\")\n");
        py.append("gx_batch_def = gx_asset.add_batch_definition_whole_dataframe(name=\"")
                .append(batchDefName).append("\")\n\n");

        // Build ExpectationSuite
        py.append("# Build Expectation Suite\n");
        py.append("gx_suite = gx.ExpectationSuite(name=\"").append(suiteName).append("\")\n");

        for (Map<String, Object> exp : expectations) {
            String type = (String) exp.get("type");
            if (type == null || type.isBlank()) continue;

            @SuppressWarnings("unchecked")
            Map<String, Object> kwargs = (Map<String, Object>) exp.getOrDefault("kwargs", Map.of());
            String severity = (String) exp.getOrDefault("severity", "critical");

            py.append("gx_suite.add_expectation(gx.expectations.").append(type).append("(");

            boolean first = true;
            for (var entry : kwargs.entrySet()) {
                if (!first) py.append(", ");
                py.append(entry.getKey()).append("=").append(toPythonLiteral(entry.getValue()));
                first = false;
            }

            py.append("))");
            py.append("  # severity: ").append(severity);
            py.append("\n");
        }

        py.append("gx_suite = gx_context.suites.add(gx_suite)\n\n");

        // Validation Definition
        py.append("# Create Validation Definition\n");
        py.append("gx_val_def = gx_context.validation_definitions.add(\n");
        py.append("    gx.ValidationDefinition(\n");
        py.append("        name=\"").append(valDefName).append("\",\n");
        py.append("        data=gx_batch_def,\n");
        py.append("        suite=gx_suite,\n");
        py.append("    )\n");
        py.append(")\n\n");

        // Checkpoint
        py.append("# Create and run Checkpoint\n");
        py.append("gx_checkpoint = gx_context.checkpoints.add(\n");
        py.append("    gx.Checkpoint(\n");
        py.append("        name=\"").append(checkpointName).append("\",\n");
        py.append("        validation_definitions=[gx_val_def],\n");
        py.append("        actions=[\n");
        py.append("            UpdateDataDocsAction(name=\"update_docs\"),\n");
        py.append("        ],\n");
        py.append("    )\n");
        py.append(")\n");
        py.append("gx_result = gx_checkpoint.run(batch_parameters={\"dataframe\": ")
                .append(safeName).append("})\n\n");

        // Result handling
        py.append("# Log validation statistics\n");
        py.append("gx_success = bool(getattr(gx_result, \"success\", False))\n");
        py.append("gx_stats = getattr(gx_result, \"statistics\", None)\n");
        py.append("if gx_stats is None:\n");
        py.append("    gx_run_results = getattr(gx_result, \"run_results\", {}) or {}\n");
        py.append("    gx_stats = {\"success\": gx_success, \"run_results_count\": len(gx_run_results)}\n");
        py.append("print(f\"GX Validation: success={gx_success}, stats={gx_stats}\")\n\n");

        // Branch based on onFailure strategy
        py.append("# Route: pass or handle failure\n");
        switch (onFailure != null ? onFailure : "fail") {
            case "quarantine" -> {
                py.append("if not gx_success:\n");
                py.append("    # Quarantine: write failed data to quarantine path\n");
                py.append("    quarantine_path = f\"${{OUTPUT_BASE}}/quarantine/").append(safeName).append("\"\n");
                py.append("    ").append(safeName).append(".write.mode(\"overwrite\").format(\"delta\").save(quarantine_path)\n");
                py.append("    import json\n");
                py.append("    print(f\"DQ QUARANTINE: Validation failed. Data written to {quarantine_path}\")\n");
                py.append("    print(f\"DQ QUARANTINE: Statistics: {gx_stats}\")\n");
                py.append("    raise Exception(\n");
                py.append("        f\"GX Data Quality validation failed for '").append(safeName)
                        .append("'. \"\n");
                py.append("        f\"Stats: {gx_stats}. Data quarantined at {quarantine_path}\"\n");
                py.append("    )\n");
                py.append("else:\n");
                py.append("    print(f\"DQ PASSED: All expectations passed for '").append(safeName).append("'\")\n");
            }
            case "warn" -> {
                py.append("if not gx_success:\n");
                py.append("    import warnings\n");
                py.append("    warnings.warn(\n");
                py.append("        f\"GX Data Quality warning for '").append(safeName)
                        .append("': {gx_stats}\",\n");
                py.append("        UserWarning\n");
                py.append("    )\n");
                py.append("    print(f\"DQ WARNING: Validation failed but continuing. Stats: {gx_stats}\")\n");
                py.append("else:\n");
                py.append("    print(f\"DQ PASSED: All expectations passed for '").append(safeName).append("'\")\n");
            }
            default -> { // "fail" -- default
                py.append("if not gx_success:\n");
                py.append("    raise Exception(\n");
                py.append("        f\"GX Data Quality validation failed for '").append(safeName)
                        .append("'. \"\n");
                py.append("        f\"Statistics: {gx_stats}\"\n");
                py.append("    )\n");
                py.append("else:\n");
                py.append("    print(f\"DQ PASSED: All expectations passed for '").append(safeName).append("'\")\n");
            }
        }

        py.append("\n");
        return py.toString();
    }

    /**
     * Generates a compact GX validation snippet for a single expectation.
     * Useful for embedding inside larger generated code blocks.
     */
    public String generateSingleExpectation(String expectationType, Map<String, Object> kwargs) {
        StringBuilder py = new StringBuilder();
        py.append("gx.expectations.").append(expectationType).append("(");
        boolean first = true;
        for (var entry : kwargs.entrySet()) {
            if (!first) py.append(", ");
            py.append(entry.getKey()).append("=").append(toPythonLiteral(entry.getValue()));
            first = false;
        }
        py.append(")");
        return py.toString();
    }

    /**
     * Converts a Java object to a Python literal representation.
     */
    String toPythonLiteral(Object value) {
        if (value == null) return "None";
        if (value instanceof Boolean) return ((Boolean) value) ? "True" : "False";
        if (value instanceof Number) return value.toString();
        if (value instanceof String) return "\"" + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (value instanceof List<?> list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(toPythonLiteral(list.get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (value instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (var entry : map.entrySet()) {
                if (!first) sb.append(", ");
                sb.append(toPythonLiteral(entry.getKey())).append(": ").append(toPythonLiteral(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + value.toString().replace("\"", "\\\"") + "\"";
    }

    /**
     * Sanitizes a variable name to be safe for Python identifiers and GX component names.
     */
    private String sanitize(String name) {
        if (name == null || name.isBlank()) return "data";
        return name.replaceAll("[^a-zA-Z0-9_]", "_").replaceAll("^_+|_+$", "");
    }
}
