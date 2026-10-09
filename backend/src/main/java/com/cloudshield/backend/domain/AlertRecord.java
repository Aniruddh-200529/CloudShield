package com.cloudshield.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "alert_records")
public class AlertRecord {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "alert_type", nullable = false, length = 100) private String alertType;
    @Column(nullable = false, length = 24) private String severity;
    @Column(nullable = false, length = 2000) private String description;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resource_id") private MonitoredResource resource;
    @Column(nullable = false, length = 24) private String status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "acknowledged_at") private Instant acknowledgedAt;
    @Column(name = "resolved_at") private Instant resolvedAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "deduplication_key", length = 240) private String deduplicationKey;
    protected AlertRecord() {}
    public AlertRecord(String alertType, String severity, String description, MonitoredResource resource, String status) { this.alertType = alertType; this.severity = severity; this.description = description; this.resource = resource; this.status = status; this.createdAt = Instant.now(); this.updatedAt = this.createdAt; }
    public AlertRecord(String alertType, String severity, String description, MonitoredResource resource, String status, String deduplicationKey) { this(alertType, severity, description, resource, status); this.deduplicationKey = deduplicationKey; }
    @PreUpdate void onUpdate() { updatedAt = Instant.now(); }
    public void changeStatus(String status) {
        if ("RESOLVED".equals(this.status) && "ACKNOWLEDGED".equals(status)) {
            throw new IllegalArgumentException("A resolved alert cannot be acknowledged; reopen it first");
        }
        if (this.status.equals(status)) return;
        Instant now = Instant.now();
        this.status = status;
        if ("ACKNOWLEDGED".equals(status) && acknowledgedAt == null) acknowledgedAt = now;
        if ("RESOLVED".equals(status) && resolvedAt == null) resolvedAt = now;
        if ("OPEN".equals(status)) { acknowledgedAt = null; resolvedAt = null; }
    }
    public UUID getId() { return id; }
    public String getAlertType() { return alertType; }
    public String getSeverity() { return severity; }
    public String getDescription() { return description; }
    public MonitoredResource getResource() { return resource; }
    public String getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public Instant getResolvedAt() { return resolvedAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getDeduplicationKey() { return deduplicationKey; }
}
