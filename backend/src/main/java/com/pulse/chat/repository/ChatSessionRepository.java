package com.pulse.chat.repository;

import com.pulse.chat.model.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSession, String> {
    List<ChatSession> findByTenantIdOrderByUpdatedAtDesc(String tenantId);
    List<ChatSession> findByTenantIdAndUserIdOrderByUpdatedAtDesc(String tenantId, String userId);
    List<ChatSession> findByPipelineIdOrderByUpdatedAtDesc(String pipelineId);
}
