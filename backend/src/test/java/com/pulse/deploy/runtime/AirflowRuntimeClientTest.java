package com.pulse.deploy.runtime;

import com.pulse.deploy.runtime.AirflowRuntimeClient.ActiveDagRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AirflowRuntimeClientTest {

    // ------------------------------------------------------------------ stub client

    @Test
    @DisplayName("StubAirflowRuntimeClient returns empty list for all calls")
    void stubClient_returnsEmptyRuns() {
        StubAirflowRuntimeClient stub = new StubAirflowRuntimeClient();

        List<ActiveDagRun> runs = stub.listActiveDagRuns(
                "pulse__acme__finance__daily-calc", "target-1", "dev");

        assertNotNull(runs);
        assertTrue(runs.isEmpty());
    }

    // ------------------------------------------------------------------ default method

    @Test
    @DisplayName("isActiveRunPresent returns false when listActiveDagRuns is empty")
    void isActiveRunPresent_defaultMethod_falseWhenEmpty() {
        AirflowRuntimeClient emptyClient = (dagId, targetId, env) -> List.of();

        assertFalse(emptyClient.isActiveRunPresent("dag-1", "target-1", "dev"));
    }

    @Test
    @DisplayName("isActiveRunPresent returns true when active runs exist")
    void isActiveRunPresent_defaultMethod_trueWhenNonEmpty() {
        AirflowRuntimeClient busyClient = (dagId, targetId, env) -> List.of(
                new ActiveDagRun("run-001", dagId, "running", Instant.now())
        );

        assertTrue(busyClient.isActiveRunPresent("dag-1", "target-1", "prod"));
    }

    // ------------------------------------------------------------------ record value type

    @Test
    @DisplayName("ActiveDagRun record holds expected values")
    void activeDagRun_holdsValues() {
        Instant now = Instant.now();
        ActiveDagRun run = new ActiveDagRun("run-42", "my-dag", "running", now);

        assertEquals("run-42", run.dagRunId());
        assertEquals("my-dag", run.dagId());
        assertEquals("running", run.state());
        assertEquals(now, run.startDate());
    }
}
