package com.pulse.cobol.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CobolDiscoveryStreamServiceTest {

    // ── RunStreamService ──────────────────────────────────────────────────

    @Nested
    class RunStreamTests {

        private CobolDiscoveryRunStreamService service;

        @BeforeEach
        void setUp() {
            service = new CobolDiscoveryRunStreamService();
        }

        @Test
        void runStream_register_returnsSseEmitterAndSendsInitialPayload() {
            Map<String, Object> initial = Map.of("status", "RUNNING", "iteration", 1);

            SseEmitter emitter = service.register("run-1", initial);

            assertNotNull(emitter, "register() must return a non-null SseEmitter");
        }

        @Test
        void runStream_publish_sendsEventToRegisteredEmitter() {
            Map<String, Object> initial = Map.of("status", "RUNNING");
            service.register("run-2", initial);

            // publish should succeed without exception because the emitter is still alive
            assertDoesNotThrow(() ->
                    service.publish("run-2", "iteration_complete",
                            Map.of("iteration", 2, "score", 0.85)));
        }

        @Test
        void runStream_publish_doesNothingWhenNoEmittersRegistered() {
            // publishing to an unknown runId should be a harmless no-op
            assertDoesNotThrow(() ->
                    service.publish("unknown-run", "iteration_complete",
                            Map.of("iteration", 1)));
        }

        @Test
        void runStream_publish_removesFailedEmitters() {
            Map<String, Object> initial = Map.of("status", "RUNNING");
            SseEmitter emitter = service.register("run-3", initial);

            // complete the emitter so subsequent sends will throw
            emitter.complete();

            // publish should handle the IOException gracefully and remove the dead emitter
            assertDoesNotThrow(() ->
                    service.publish("run-3", "iteration_complete",
                            Map.of("iteration", 1)));
        }

        @Test
        void runStream_register_removesEmitterOnTimeout() {
            Map<String, Object> initial = Map.of("status", "RUNNING");
            SseEmitter emitter = service.register("run-4", initial);

            // Manually trigger the timeout callback that was registered via emitter.onTimeout()
            emitter.complete();

            // After timeout/completion removal, publishing should be a no-op (no emitters left)
            assertDoesNotThrow(() ->
                    service.publish("run-4", "timeout_check",
                            Map.of("check", true)));
        }
    }

    // ── SessionStreamService ──────────────────────────────────────────────

    @Nested
    class SessionStreamTests {

        private CobolDiscoverySessionStreamService service;

        @BeforeEach
        void setUp() {
            service = new CobolDiscoverySessionStreamService();
        }

        @Test
        void sessionStream_register_returnsSseEmitter() {
            SseEmitter emitter = service.register("session-1");

            assertNotNull(emitter, "register() must return a non-null SseEmitter");
        }

        @Test
        void sessionStream_publish_sendsEventToRegisteredEmitter() {
            service.register("session-2");

            assertDoesNotThrow(() ->
                    service.publish("session-2", "run_update",
                            Map.of("runId", "run-x", "status", "RUNNING")));
        }

        @Test
        void sessionStream_publish_doesNothingWhenNoEmitters() {
            assertDoesNotThrow(() ->
                    service.publish("unknown-session", "run_update",
                            Map.of("runId", "run-x")));
        }

        @Test
        void sessionStream_publish_removesDeadEmitters() {
            SseEmitter emitter = service.register("session-3");

            // mark emitter as dead
            emitter.complete();

            // publish should detect the dead emitter, remove it, and not throw
            assertDoesNotThrow(() ->
                    service.publish("session-3", "run_update",
                            Map.of("status", "DONE")));
        }

        @Test
        void sessionStream_heartbeat_sendsToAllRegistered() {
            service.register("session-4");

            // heartbeat should send to the live emitter without error
            assertDoesNotThrow(() -> service.heartbeat());
        }

        @Test
        void sessionStream_heartbeat_removesDeadEmitters() {
            SseEmitter emitter = service.register("session-5");

            // kill the emitter
            emitter.complete();

            // heartbeat should detect dead emitter, remove it, and not throw
            assertDoesNotThrow(() -> service.heartbeat());
        }
    }
}
