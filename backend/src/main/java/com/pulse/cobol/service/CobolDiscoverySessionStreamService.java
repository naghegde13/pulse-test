package com.pulse.cobol.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class CobolDiscoverySessionStreamService {

    private static final Logger log = LoggerFactory.getLogger(CobolDiscoverySessionStreamService.class);
    private static final long EMITTER_TIMEOUT = 0L; // no timeout, we manage lifecycle via heartbeat failures

    private final Map<String, CopyOnWriteArrayList<SseEmitter>> emittersBySession = new ConcurrentHashMap<>();
    private final Set<SseEmitter> dead = Collections.newSetFromMap(new ConcurrentHashMap<>());

    public SseEmitter register(String sessionId) {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT);
        emittersBySession.computeIfAbsent(sessionId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        Runnable cleanup = () -> {
            dead.add(emitter);
            remove(sessionId, emitter);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(cleanup);
        emitter.onError(error -> cleanup.run());
        return emitter;
    }

    public void publish(String sessionId, String eventName, Map<String, Object> payload) {
        List<SseEmitter> emitters = emittersBySession.get(sessionId);
        if (emitters == null || emitters.isEmpty()) return;
        for (SseEmitter emitter : emitters) {
            if (dead.contains(emitter)) {
                remove(sessionId, emitter);
                continue;
            }
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .id(Instant.now().toString())
                        .data(payload));
            } catch (Exception ex) {
                dead.add(emitter);
                remove(sessionId, emitter);
            }
        }
    }

    @Scheduled(fixedRate = 15_000)
    public void heartbeat() {
        for (Map.Entry<String, CopyOnWriteArrayList<SseEmitter>> entry : emittersBySession.entrySet()) {
            for (SseEmitter emitter : entry.getValue()) {
                if (dead.contains(emitter)) {
                    remove(entry.getKey(), emitter);
                    continue;
                }
                try {
                    emitter.send(SseEmitter.event()
                            .name("heartbeat")
                            .data(Map.of("ts", Instant.now().toString())));
                } catch (Exception ex) {
                    dead.add(emitter);
                    remove(entry.getKey(), emitter);
                }
            }
        }
    }

    private void remove(String sessionId, SseEmitter emitter) {
        List<SseEmitter> emitters = emittersBySession.get(sessionId);
        if (emitters == null) return;
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersBySession.remove(sessionId);
        }
    }
}
