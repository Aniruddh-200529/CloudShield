package com.cloudshield.backend.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name = "alert_status_history")
public class AlertStatusHistory {
    @Id @GeneratedValue(strategy = GenerationType.UUID) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "alert_id", nullable = false) private AlertRecord alert;
    @Column(name = "previous_status", length = 24) private String previousStatus;
    @Column(name = "new_status", nullable = false, length = 24) private String newStatus;
    @Column(name = "actor_identifier", length = 160) private String actorIdentifier;
    @Column(name = "changed_at", nullable = false) private Instant changedAt = Instant.now();
    protected AlertStatusHistory() {}
    public AlertStatusHistory(AlertRecord alert, String previousStatus, String newStatus, String actorIdentifier) {
        this.alert = alert; this.previousStatus = previousStatus; this.newStatus = newStatus;
        this.actorIdentifier = actorIdentifier; this.changedAt = Instant.now();
    }
    public UUID getId() { return id; }
    public String getPreviousStatus() { return previousStatus; }
    public String getNewStatus() { return newStatus; }
    public String getActorIdentifier() { return actorIdentifier; }
    public Instant getChangedAt() { return changedAt; }
}
