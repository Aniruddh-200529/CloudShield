package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.AlertStatusHistory;
import java.time.Instant;
import java.util.UUID;

public record AlertHistoryResponse(UUID id, String previousStatus, String newStatus, String actorIdentifier, Instant changedAt) {
    public static AlertHistoryResponse from(AlertStatusHistory h) { return new AlertHistoryResponse(h.getId(), h.getPreviousStatus(), h.getNewStatus(), h.getActorIdentifier(), h.getChangedAt()); }
}
