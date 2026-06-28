package com.pulse.e2e.builder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulse.blueprint.repository.BlueprintRepository;
import com.pulse.codegen.model.GeneratedArtifact;
import com.pulse.e2e.LoanMasterFixture;
import com.pulse.e2e.api.ApiScenarioClient;
import com.pulse.e2e.contract.ScenarioDsl;
import com.pulse.pipeline.model.SubPipelineInstance;
import com.pulse.sor.repository.ConnectorDefinitionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "pulse.git.local-repo-base=${java.io.tmpdir}/pulse-e2e-advance-time-builder-repos")
@Transactional
class ApiScenarioBuilderAdvanceTimeDimensionIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private BlueprintRepository blueprintRepository;
    @Autowired private ConnectorDefinitionRepository connectorDefinitionRepository;

    @Test
    void buildsAdvanceTimeDimensionScenarioWithStandaloneRuntimeStateContract() throws Exception {
        ApiScenarioBuilder builder = new ApiScenarioBuilder(
                new ApiScenarioClient(mockMvc, objectMapper),
                blueprintRepository,
                connectorDefinitionRepository
        );

        var execution = builder.execute(scenario(), LoanMasterFixture.loadCanonical());

        SubPipelineInstance advance = execution.instances().stream()
                .filter(instance -> "AdvanceTimeDimension".equals(instance.getBlueprintKey()))
                .findFirst()
                .orElseThrow();
        Map<String, Object> params = advance.getParams();

        assertEquals("dataset", params.get("target_scope"));
        assertNotNull(params.get("state_binding_ref"));
        assertNotNull(params.get("variable_key"));
        assertNotNull(params.get("calendar_binding_ref"));
        assertNotNull(params.get("calendar_bundle_uri"));
        assertNotNull(params.get("evidence_prefix"));

        String dag = execution.artifacts().stream()
                .filter(artifact -> "AIRFLOW_DAG".equals(artifact.getFileType()))
                .map(GeneratedArtifact::getContent)
                .findFirst()
                .orElseThrow();
        assertTrue(dag.contains("from pulse_airflow_runtime.time_state import AdvanceTimeDimensionOperator"));
        assertTrue(dag.contains("AdvanceTimeDimensionOperator("));
        assertTrue(dag.contains("'state_binding_ref': ") || dag.contains("\"state_binding_ref\" : "));
        assertTrue(dag.contains("'variable_key': ") || dag.contains("\"variable_key\" : "));
        assertTrue(dag.contains("'requested_asof_expr': '2026-03-03'")
                || dag.contains("\"requested_asof_expr\" : \"2026-03-03\""));
        assertTrue(dag.contains("'concurrency_policy': 'serialized_airflow'")
                || dag.contains("\"concurrency_policy\" : \"serialized_airflow\""));
        assertTrue(dag.contains("pool='pulse_time_state_"));
        assertTrue(!dag.contains("/api/v1/tenants/"));
    }

    private ScenarioDsl.ScenarioDefinition scenario() {
        return new ScenarioDsl.ScenarioDefinition(
                "advance-time-dimension-api-contract",
                "AdvanceTimeDimension API contract proof",
                ScenarioDsl.ProofMode.ARTIFACT_ONLY,
                ScenarioDsl.RuntimeAdapter.LOCAL_AIRFLOW_BRIDGE,
                List.of("advance_time_dimension", "dataset_advance"),
                new ScenarioDsl.BuilderPlan(
                        "tenant-advtime",
                        "servicing",
                        "loan_master",
                        List.of("AdvanceTimeDimension", "FileIngestion", "GenericFilter", "DatabaseWriter"),
                        "loan_master"
                ),
                new ScenarioDsl.EvidenceExpectation(List.of(), List.of(), null),
                Map.of()
        );
    }
}
