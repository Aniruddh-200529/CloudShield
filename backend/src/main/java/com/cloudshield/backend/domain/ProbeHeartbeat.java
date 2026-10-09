package com.cloudshield.backend.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "probe_heartbeats")
public class ProbeHeartbeat {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @Column(name = "probe_identifier", nullable = false, length = 160) private String probeIdentifier;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "resource_id") private MonitoredResource resource;
    @Column(nullable = false, length = 32) private String status;
    @Column(name = "health_message", length = 500) private String healthMessage;
    @Column(name = "received_at", nullable = false) private Instant receivedAt;
    protected ProbeHeartbeat() {}
    public ProbeHeartbeat(String probeIdentifier, MonitoredResource resource, String status, String healthMessage, Instant receivedAt) { this.probeIdentifier = probeIdentifier; this.resource = resource; this.status = status; this.healthMessage = healthMessage; this.receivedAt = receivedAt; }
    public UUID getId() { return id; }
    public String getProbeIdentifier() { return probeIdentifier; }
    public MonitoredResource getResource() { return resource; }
    public String getStatus() { return status; }
    public String getHealthMessage() { return healthMessage; }
    public Instant getReceivedAt() { return receivedAt; }
}
