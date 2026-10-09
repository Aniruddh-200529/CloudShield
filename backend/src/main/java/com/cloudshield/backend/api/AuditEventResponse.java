package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.AuditEvent;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventResponse(UUID id, String eventType, String actorIdentifier, String targetIdentifier, UUID resourceId, Instant occurredAt, String outcome, Map<String, Object> details) {
    public static AuditEventResponse from(AuditEvent e) { return new AuditEventResponse(e.getId(), e.getEventType(), e.getActorIdentifier(), e.getTargetIdentifier(), e.getResource() == null ? null : e.getResource().getId(), e.getOccurredAt(), e.getOutcome(), e.getDetails()); }
}
