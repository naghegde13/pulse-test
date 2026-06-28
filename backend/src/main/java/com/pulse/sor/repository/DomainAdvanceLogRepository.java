package com.pulse.sor.repository;

import com.pulse.sor.model.DomainAdvanceLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DomainAdvanceLogRepository extends JpaRepository<DomainAdvanceLog, String> {
    List<DomainAdvanceLog> findByDomainIdOrderByCreatedAtDesc(String domainId);
}
