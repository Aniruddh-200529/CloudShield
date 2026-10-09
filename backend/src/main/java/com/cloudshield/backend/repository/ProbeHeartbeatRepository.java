package com.cloudshield.backend.repository;

import com.cloudshield.backend.domain.ProbeHeartbeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProbeHeartbeatRepository extends JpaRepository<ProbeHeartbeat, UUID> {
    List<ProbeHeartbeat> findByProbeIdentifierOrderByReceivedAtDesc(String probeIdentifier, Pageable pageable);
    List<ProbeHeartbeat> findAllByOrderByReceivedAtDesc(Pageable pageable);
}
