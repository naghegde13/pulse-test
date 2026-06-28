package com.pulse.chat.service;

import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 7 (IMPL-ui-composition) — {@code truncateMessagesAfter} units (pure,
 * in-memory repo): a Restore deletes the anchor message AND everything after it
 * (the n8n {@code truncateMessagesAfter(messageId)} analogue), leaving the
 * earlier messages intact; an unknown anchor is a no-op.
 */
class ChatHistoryServiceTest {

    private InMemoryMessageRepo repo;
    private ChatHistoryService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryMessageRepo();
        service = new ChatHistoryService(repo);
    }

    private ChatMessage save(String sessionId, String role, String content, long epochMillis) {
        ChatMessage m = new ChatMessage();
        // Drive @PrePersist's id + createdAt, then override createdAt for ordering.
        m.setSessionId(sessionId);
        m.setRole(role);
        m.setContent(content);
        ChatMessage saved = repo.save(m);
        setCreatedAt(saved, Instant.ofEpochMilli(epochMillis));
        return saved;
    }

    @Test
    void truncateDeletesAnchorAndEverythingAfter() {
        String s = "session-1";
        save(s, "USER", "m0", 1_000);
        save(s, "ASSISTANT", "m1", 2_000);
        ChatMessage anchor = save(s, "USER", "m2-anchor", 3_000);
        save(s, "ASSISTANT", "m3", 4_000);
        save(s, "USER", "m4", 5_000);

        int deleted = service.truncateMessagesAfter(s, anchor.getId());

        assertEquals(3, deleted, "anchor + 2 after it deleted");
        List<ChatMessage> left = repo.findBySessionIdOrderByCreatedAtAsc(s);
        assertEquals(2, left.size(), "two earlier messages remain");
        assertEquals("m0", left.get(0).getContent());
        assertEquals("m1", left.get(1).getContent());
        assertFalse(left.stream().anyMatch(m -> m.getId().equals(anchor.getId())),
                "anchor itself is deleted");
    }

    @Test
    void unknownAnchorIsNoOp() {
        String s = "session-2";
        save(s, "USER", "a", 1_000);
        save(s, "ASSISTANT", "b", 2_000);

        int deleted = service.truncateMessagesAfter(s, "01JNONEXISTENT00000000000");
        assertEquals(0, deleted, "unknown anchor deletes nothing");
        assertEquals(2, repo.findBySessionIdOrderByCreatedAtAsc(s).size(), "history intact");
    }

    @Test
    void blankAnchorIsNoOp() {
        String s = "session-3";
        save(s, "USER", "a", 1_000);
        assertEquals(0, service.truncateMessagesAfter(s, ""));
        assertEquals(0, service.truncateMessagesAfter(s, null));
        assertEquals(1, repo.findBySessionIdOrderByCreatedAtAsc(s).size());
    }

    @Test
    void anchorAtHeadDeletesEntireSession() {
        String s = "session-4";
        ChatMessage anchor = save(s, "USER", "first", 1_000);
        save(s, "ASSISTANT", "second", 2_000);
        int deleted = service.truncateMessagesAfter(s, anchor.getId());
        assertEquals(2, deleted);
        assertTrue(repo.findBySessionIdOrderByCreatedAtAsc(s).isEmpty());
    }

    // ---- helper: force createdAt for deterministic ordering ----

    private static void setCreatedAt(ChatMessage m, Instant when) {
        try {
            Field f = ChatMessage.class.getDeclaredField("createdAt");
            f.setAccessible(true);
            f.set(m, when);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Minimal in-memory {@link ChatMessageRepository} — only the methods the
     * service touches ({@code save}, {@code findBySessionIdOrderByCreatedAtAsc},
     * {@code deleteAll}). All other JpaRepository methods are unsupported.
     */
    private static final class InMemoryMessageRepo implements ChatMessageRepository {
        private final Map<String, ChatMessage> store = new LinkedHashMap<>();

        @Override
        public List<ChatMessage> findBySessionIdOrderByCreatedAtAsc(String sessionId) {
            List<ChatMessage> out = new ArrayList<>();
            for (ChatMessage m : store.values()) {
                if (sessionId.equals(m.getSessionId())) out.add(m);
            }
            out.sort(Comparator.comparing(ChatMessage::getCreatedAt)
                    .thenComparing(ChatMessage::getId));
            return out;
        }

        @Override
        public <S extends ChatMessage> S save(S entity) {
            // Mimic @PrePersist (id + createdAt) without a real EntityManager.
            if (entity.getId() == null) {
                entity.setId(com.github.f4b6a3.ulid.UlidCreator.getMonotonicUlid().toString());
            }
            if (entity.getCreatedAt() == null) {
                setCreatedAt(entity, Instant.now());
            }
            store.put(entity.getId(), entity);
            return entity;
        }

        @Override
        public void deleteAll(Iterable<? extends ChatMessage> entities) {
            for (ChatMessage m : entities) store.remove(m.getId());
        }

        // ---- unused JpaRepository surface ----
        @Override public void flush() {}
        @Override public <S extends ChatMessage> S saveAndFlush(S entity) { return save(entity); }
        @Override public <S extends ChatMessage> List<S> saveAllAndFlush(Iterable<S> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch(Iterable<ChatMessage> entities) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllByIdInBatch(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public void deleteAllInBatch() { throw new UnsupportedOperationException(); }
        @Override public ChatMessage getOne(String id) { throw new UnsupportedOperationException(); }
        @Override public ChatMessage getById(String id) { throw new UnsupportedOperationException(); }
        @Override public ChatMessage getReferenceById(String id) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> List<S> findAll(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> List<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> List<S> saveAll(Iterable<S> entities) { List<S> r = new ArrayList<>(); entities.forEach(e -> r.add(save(e))); return r; }
        @Override public java.util.Optional<ChatMessage> findById(String id) { return java.util.Optional.ofNullable(store.get(id)); }
        @Override public boolean existsById(String id) { return store.containsKey(id); }
        @Override public List<ChatMessage> findAll() { return new ArrayList<>(store.values()); }
        @Override public List<ChatMessage> findAllById(Iterable<String> ids) { throw new UnsupportedOperationException(); }
        @Override public long count() { return store.size(); }
        @Override public void deleteById(String id) { store.remove(id); }
        @Override public void delete(ChatMessage entity) { store.remove(entity.getId()); }
        @Override public void deleteAllById(Iterable<? extends String> ids) { ids.forEach(store::remove); }
        @Override public void deleteAll() { store.clear(); }
        @Override public List<ChatMessage> findAll(org.springframework.data.domain.Sort sort) { throw new UnsupportedOperationException(); }
        @Override public org.springframework.data.domain.Page<ChatMessage> findAll(org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> java.util.Optional<S> findOne(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> org.springframework.data.domain.Page<S> findAll(org.springframework.data.domain.Example<S> example, org.springframework.data.domain.Pageable pageable) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> long count(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage> boolean exists(org.springframework.data.domain.Example<S> example) { throw new UnsupportedOperationException(); }
        @Override public <S extends ChatMessage, R> R findBy(org.springframework.data.domain.Example<S> example, java.util.function.Function<org.springframework.data.repository.query.FluentQuery.FetchableFluentQuery<S>, R> queryFunction) { throw new UnsupportedOperationException(); }
    }
}
