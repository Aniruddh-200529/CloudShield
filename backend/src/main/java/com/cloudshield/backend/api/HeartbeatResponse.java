package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.ProbeHeartbeat;
import java.time.Instant;
import java.util.UUID;

public record HeartbeatResponse(UUID id, String probeIdentifier, UUID resourceId, String status, String healthMessage, Instant receivedAt) {
    public static HeartbeatResponse from(ProbeHeartbeat h) { return new HeartbeatResponse(h.getId(), h.getProbeIdentifier(), h.getResource() == null ? null : h.getResource().getId(), h.getStatus(), h.getHealthMessage(), h.getReceivedAt()); }
}
