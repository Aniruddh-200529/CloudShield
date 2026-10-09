package com.cloudshield.backend.service;

import com.cloudshield.backend.api.AuditEventRequest;
import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.repository.AuditEventRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventService {
    private final AuditEventRepository repository;
    private final ResourceService resources;
    public AuditEventService(AuditEventRepository repository, ResourceService resources) { this.repository = repository; this.resources = resources; }
    @Transactional public AuditEvent record(AuditEventRequest request) { return repository.save(new AuditEvent(request.eventType(), request.actorIdentifier(), request.targetIdentifier(), request.resourceId() == null ? null : resources.get(request.resourceId()), request.occurredAt() == null ? Instant.now() : request.occurredAt(), request.outcome(), request.details())); }
    @Transactional(readOnly = true) public List<AuditEvent> list(int limit) { return repository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, limit)); }
}
