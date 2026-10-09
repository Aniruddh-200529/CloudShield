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
    public HeartbeatService(ProbeHeartbeatRepository repository, ResourceService resources) { this.repository = repository; this.resources = resources; }
    @Transactional public ProbeHeartbeat record(HeartbeatRequest request) { return repository.save(new ProbeHeartbeat(request.probeIdentifier(), request.resourceId() == null ? null : resources.get(request.resourceId()), request.status(), request.healthMessage(), request.receivedAt() == null ? Instant.now() : request.receivedAt())); }
    @Transactional(readOnly = true) public List<ProbeHeartbeat> list(String probeIdentifier, int limit) {
        return probeIdentifier == null ? repository.findAllByOrderByReceivedAtDesc(PageRequest.of(0, limit)) : repository.findByProbeIdentifierOrderByReceivedAtDesc(probeIdentifier, PageRequest.of(0, limit));
    }
}
