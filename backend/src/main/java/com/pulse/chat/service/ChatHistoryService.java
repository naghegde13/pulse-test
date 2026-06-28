package com.pulse.chat.service;

import com.pulse.chat.model.ChatMessage;
import com.pulse.chat.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Chat-history maintenance for snapshot/revert (IMPL-ui-composition Phase 7;
 * 07-orchestration-revert-layout.md §2). Owns the message-anchored truncation
 * that an already-applied-turn <b>Restore</b> performs — the PULSE analogue of
 * n8n's {@code truncateMessagesAfter(messageId)} ({@code [read I]}, §2.1): when
 * the Customer restores to a turn, the chat is rewound by deleting the turn's
 * <b>anchor message and every message after it</b>, so the conversation matches
 * the restored composition.
 *
 * <p>Kept as a focused, H2-testable service (no checkpointer dependency): the
 * checkpoint-snapshot restore is the {@link ChatService}/{@code GraphDriver}
 * concern; this class is purely the {@code chat_messages} truncation half.</p>
 */
@Service
public class ChatHistoryService {

    private static final Logger log = LoggerFactory.getLogger(ChatHistoryService.class);

    private final ChatMessageRepository messageRepo;

    public ChatHistoryService(ChatMessageRepository messageRepo) {
        this.messageRepo = messageRepo;
    }

    /**
     * Delete the anchor message and every message created at-or-after it in the
     * session — the {@code truncateMessagesAfter(messageId)} analogue ({@code
     * [read I]}). Ordering is by {@code createdAt} (the same order the chat is
     * rebuilt in, {@code findBySessionIdOrderByCreatedAtAsc}); ties on
     * {@code createdAt} are broken by the monotonic ULID {@code id} so the cut is
     * deterministic.
     *
     * @return the number of messages deleted (0 if the anchor is not in the
     *         session — a no-op, never an error).
     */
    @Transactional
    public int truncateMessagesAfter(String sessionId, String anchorMessageId) {
        if (sessionId == null || anchorMessageId == null || anchorMessageId.isBlank()) {
            return 0;
        }
        List<ChatMessage> ordered = messageRepo.findBySessionIdOrderByCreatedAtAsc(sessionId);
        ChatMessage anchor = null;
        for (ChatMessage m : ordered) {
            if (anchorMessageId.equals(m.getId())) {
                anchor = m;
                break;
            }
        }
        if (anchor == null) {
            log.debug("truncateMessagesAfter: anchor {} not found in session {} (no-op).",
                    anchorMessageId, sessionId);
            return 0;
        }
        final ChatMessage cut = anchor;
        List<ChatMessage> toDelete = new ArrayList<>();
        for (ChatMessage m : ordered) {
            if (isAtOrAfter(m, cut)) {
                toDelete.add(m);
            }
        }
        if (!toDelete.isEmpty()) {
            messageRepo.deleteAll(toDelete);
        }
        log.info("truncateMessagesAfter: session={} anchor={} deleted={} messages.",
                sessionId, anchorMessageId, toDelete.size());
        return toDelete.size();
    }

    /**
     * A message is at-or-after the cut when its {@code createdAt} is later, or
     * equal-{@code createdAt} with an id &ge; the cut's id (ULIDs are monotonic,
     * so id order == insertion order on a tie).
     */
    private static boolean isAtOrAfter(ChatMessage m, ChatMessage cut) {
        int byTime = m.getCreatedAt().compareTo(cut.getCreatedAt());
        if (byTime > 0) return true;
        if (byTime < 0) return false;
        return m.getId().compareTo(cut.getId()) >= 0;
    }
}
