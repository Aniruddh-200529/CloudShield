package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.AuditEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditEventRepository extends JpaRepository<AuditEvent, UUID> {
    List<AuditEvent> findAllByOrderByOccurredAtDesc(Pageable pageable);
}
