package com.pulse.e2e.builder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiScenarioBuilderTest {

    @Test
    void advanceTimeDimensionParamsUseDatasetAdvanceContract() {
        var params = ApiScenarioBuilder.advanceTimeDimensionDatasetAdvanceParams(
                "tenant-proof", "sor-proof", "connector-proof", "dataset-loan-master");

        assertEquals("loan_master", params.get("dataset_name"));
        assertEquals("dataset", params.get("target_scope"));
        assertEquals("time_state:dataset:dataset-loan-master", params.get("state_binding_ref"));
        assertEquals("pulse.time_state.tenant_proof.time_state_dataset_dataset-loan-master", params.get("variable_key"));
        assertEquals("calendar:tenant-proof:sor-proof", params.get("calendar_binding_ref"));
        assertEquals("runtime/calendar-bundles/dataset-loan-master.json", params.get("calendar_bundle_uri"));
        assertEquals("2026-03-03", params.get("requested_asof_expr"));
        assertEquals("require_existing", params.get("initialization_policy"));
        assertEquals("serialized_airflow", params.get("concurrency_policy"));
        assertTrue(params.get("advanced_by").toString().startsWith("airflow:"));
        assertEquals("semantic-proof", params.get("source"));
    }
}
