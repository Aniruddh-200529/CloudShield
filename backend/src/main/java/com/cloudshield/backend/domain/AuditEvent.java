package com.cloudshield.backend.domain;

import com.cloudshield.backend.persistence.JsonMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity @Table(name = "audit_events")
public class AuditEvent {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "event_type", nullable = false, length = 100) private String eventType;
    @Column(name = "actor_identifier", length = 160) private String actorIdentifier;
    @Column(name = "target_identifier", length = 160) private String targetIdentifier;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resource_id") private MonitoredResource resource;
    @Column(name = "occurred_at", nullable = false) private Instant occurredAt;
    @Column(nullable = false, length = 32) private String outcome;
    @Convert(converter = JsonMapConverter.class) @Column(columnDefinition = "text") private Map<String, Object> details;
    protected AuditEvent() {}
    public AuditEvent(String eventType, String actorIdentifier, String targetIdentifier, MonitoredResource resource, Instant occurredAt, String outcome, Map<String, Object> details) { this.eventType = eventType; this.actorIdentifier = actorIdentifier; this.targetIdentifier = targetIdentifier; this.resource = resource; this.occurredAt = occurredAt; this.outcome = outcome; this.details = details; }
    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getActorIdentifier() { return actorIdentifier; }
    public String getTargetIdentifier() { return targetIdentifier; }
    public MonitoredResource getResource() { return resource; }
    public Instant getOccurredAt() { return occurredAt; }
    public String getOutcome() { return outcome; }
    public Map<String, Object> getDetails() { return details; }
}
