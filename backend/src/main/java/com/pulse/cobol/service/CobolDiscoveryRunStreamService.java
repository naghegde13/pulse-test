package com.pulse.cobol.service;

import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CobolDiscoveryRunStreamService {

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersByRun = new ConcurrentHashMap<>();

    public SseEmitter register(String runId, Map<String, Object> initialPayload) {
        SseEmitter emitter = new SseEmitter(120_000L);
        emittersByRun.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(runId, emitter));
        emitter.onTimeout(() -> remove(runId, emitter));
        emitter.onError(error -> remove(runId, emitter));
        try {
            emitter.send(SseEmitter.event()
                    .name("run_status")
                    .id(Instant.now().toString())
                    .data(initialPayload));
        } catch (Exception ex) {
            remove(runId, emitter);
        }
        return emitter;
    }

    public void publish(String runId, String eventName, Map<String, Object> payload) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .id(Instant.now().toString())
                        .data(payload));
            } catch (Exception ex) {
                remove(runId, emitter);
            }
        }
    }

    private void remove(String runId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersByRun.get(runId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByRun.remove(runId);
        }
    }
}
