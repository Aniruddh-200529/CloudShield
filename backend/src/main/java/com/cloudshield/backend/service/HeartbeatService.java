package com.cloudshield.backend.service;

import com.cloudshield.backend.api.HeartbeatRequest;
import com.cloudshield.backend.domain.ProbeHeartbeat;
import com.cloudshield.backend.repository.ProbeHeartbeatRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class HeartbeatService {
    private final ProbeHeartbeatRepository repository;
    private final ResourceService resources;
    private final AlertService alerts;
    public HeartbeatService(ProbeHeartbeatRepository repository, ResourceService resources, AlertService alerts) { this.repository = repository; this.resources = resources; this.alerts = alerts; }
    @Transactional public ProbeHeartbeat record(HeartbeatRequest request) {
        if (request.resourceId() != null && request.resourceIdentifier() != null) throw new IllegalArgumentException("Specify resourceId or resourceIdentifier, not both");
        Instant received = request.receivedAt() == null ? Instant.now() : request.receivedAt();
        if (received.isAfter(Instant.now().plusSeconds(300)) || received.isBefore(Instant.now().minusSeconds(86400))) throw new IllegalArgumentException("Heartbeat timestamp is outside the accepted time window");
        var resource = request.resourceId() != null ? resources.lock(request.resourceId()) : null;
        if (request.resourceIdentifier() != null) resource = resources.lockByIdentifier(request.resourceIdentifier());
        var saved = repository.save(new ProbeHeartbeat(request.probeIdentifier().trim(), resource, request.status(), request.healthMessage(), received));
        if (resource != null) {
            if ("HEALTHY".equals(request.status())) alerts.resolveProbeStale(resource);
            else alerts.evaluateProbeStale(resource);
        }
        return saved;
    }
    @Transactional(readOnly = true) public List<ProbeHeartbeat> list(String probeIdentifier, int limit) {
        return probeIdentifier == null ? repository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, limit)) : repository.findByProbeIdentifierOrderByReceivedAtDesc(probeIdentifier, PageRequest.of(0, limit));
    }
}
