package com.cloudshield.backend.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record AuditEventRequest(@NotBlank @Size(max = 100) String eventType, @Size(max = 160) String actorIdentifier, @Size(max = 160) String targetIdentifier, UUID resourceId, Instant occurredAt, @NotBlank @Size(max = 32) String outcome, Map<String, Object> details) {}
