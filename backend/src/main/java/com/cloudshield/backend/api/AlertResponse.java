package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.AlertRecord;
import java.time.Instant;
import java.util.UUID;

public record AlertResponse(UUID id, String alertType, String severity, String description, UUID resourceId, String status, Instant createdAt, Instant acknowledgedAt, Instant resolvedAt, Instant updatedAt) {
    public static AlertResponse from(AlertRecord a) { return new AlertResponse(a.getId(), a.getAlertType(), a.getSeverity(), a.getDescription(), a.getResource() == null ? null : a.getResource().getId(), a.getStatus(), a.getCreatedAt(), a.getAcknowledgedAt(), a.getResolvedAt(), a.getUpdatedAt()); }
}
