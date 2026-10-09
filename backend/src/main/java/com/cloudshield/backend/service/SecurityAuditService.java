package com.cloudshield.backend.service;

import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuditService {
    private final AuditEventRepository events;
    public SecurityAuditService(AuditEventRepository events) { this.events = events; }
    @Transactional public void record(String event, String actor, String target, String outcome, Map<String, Object> details) {
        events.save(new AuditEvent(event, actor, target, null, Instant.now(), outcome, details));
    }
}
