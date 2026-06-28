package com.pulse.codegen.opengine;

import com.pulse.pipeline.opengine.OpList;
import com.pulse.pipeline.opengine.OpVocabulary;
import com.pulse.pipeline.opengine.ResolvedConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Phase 4 — DAG emitter (data-aware edges) + config-externalization (SPEC #2 §C.3, §C.7). */
class Phase4DagAndConfigTest {

    // ---- data-aware URIs (SPEC #2 §C.7, G-C4) ----------------------------

    @Test
    void canonicalUriScheme() {
        // dataset "loan_master" slugs to "loan-master" (canonical, deterministic).
        assertEquals("pulse://home-lending/servicing/loan-master",
                DataAwareUri.of("home-lending", "servicing", "loan_master"));
    }

    @Test
    void producerOutletsAndConsumerScheduleMatch() {
        String uri = DataAwareUri.of("t", "d", "ds");
        assertEquals("outlets=[Dataset(\"" + uri + "\")]", DataAwareUri.outlets(uri));
        assertEquals("schedule=[Dataset(\"" + uri + "\")]", DataAwareUri.scheduleOn(uri));
    }

    @Test
    void blankUriSegmentLoudFails() {
        assertThrows(EmissionException.class, () -> DataAwareUri.of("t", "", "ds"));
    }

    // ---- config externalization (SPEC #2 §C.3, G-C2) --------------------

    @Test
    void sliceFileForEachEnv() {
        assertEquals("config/dev.yaml", ConfigExternalizer.sliceFile("dev"));
        assertEquals("config/prod.yaml", ConfigExternalizer.sliceFile("prod"));
    }

    @Test
    void unknownEnvLoudFails() {
        assertThrows(EmissionException.class, () -> ConfigExternalizer.sliceFile("staging"));
    }

    @Test
    void pythonLoaderSelectsByPulseEnv() {
        String loader = ConfigExternalizer.pythonLoader();
        assertTrue(loader.contains("os.environ.get('PULSE_ENV', 'dev')"));
        assertTrue(loader.contains("config/{PULSE_ENV}.yaml"));
    }

    @Test
    void renderSliceIsDeterministicAndNoBakedLiteralsInCode() {
        Map<String, Object> vals = new LinkedHashMap<>();
        vals.put("gcp_project", "pulse-prod-123");
        vals.put("gcs_bucket", "pulse-prod-bucket");
        String yaml = ConfigExternalizer.renderSlice("prod", vals);
        assertTrue(yaml.contains("gcp_project: pulse-prod-123"));
        assertTrue(yaml.contains("gcs_bucket: pulse-prod-bucket"));
        assertTrue(yaml.startsWith("# config/prod.yaml"));
    }

    // ---- DAG assembly (one DAG/pipeline; control ops as own elements) ----

    @Test
    void assemblesControlAndDataNodesWithDataAwareEdges() {
        ModeResolver mr = new ModeResolver(
                org.mockito.Mockito.mock(com.pulse.runtime.service.RuntimeAuthorityService.class));
        DagOnlyEmitter dagOnly = new DagOnlyEmitter(buildSenseRegistry());
        DagAssembler assembler = new DagAssembler(dagOnly);

        EmitContext senseCtx = EmitContext.builder()
                .mode(Mode.GCP_PULSE).modeResolver(mr)
                .config(new ResolvedConfig(Map.of("sense_type", "file", "task_id", "wait_file")))
                .build();

        String producerUri = DataAwareUri.of("home-lending", "servicing", "loan_master");
        List<DagAssembler.DagNode> nodes = List.of(
                DagAssembler.DagNode.control("wait_file",
                        new OpList.OpEntry(OpVocabulary.SENSE, "Sense", Map.of("sense_type", "file")), senseCtx),
                DagAssembler.DagNode.dataTask("bronze_task",
                        "bronze_task = DataprocCreateBatchOperator(task_id='bronze_task')\n",
                        List.of(producerUri), List.of()));

        DagAssembler.Result r = assembler.assemble("loan_master_dag", nodes);
        assertTrue(r.dagBody().contains("# DAG: loan_master_dag"));
        assertTrue(r.dagBody().contains("control op: sense"));
        assertTrue(r.dagBody().contains("Sensor"));            // the control op emits its sensor
        assertTrue(r.dagBody().contains("bronze_task ="));      // the data task
        assertTrue(r.dagBody().contains("outlets=[Dataset(\"" + producerUri + "\")]"));
    }

    private HandlerRegistry buildSenseRegistry() {
        // Build a registry with the real handlers so dagOnly can resolve "sense".
        ModeResolver mr = new ModeResolver(
                org.mockito.Mockito.mock(com.pulse.runtime.service.RuntimeAuthorityService.class));
        return new CodegenOpEngine(mr).registry();
    }
}
