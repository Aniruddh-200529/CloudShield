package com.cloudshield.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudshield.backend.domain.MonitoredResource;
import com.cloudshield.backend.domain.ProbeHeartbeat;
import com.cloudshield.backend.repository.MonitoredResourceRepository;
import com.cloudshield.backend.repository.ProbeHeartbeatRepository;
import com.cloudshield.backend.service.AlertService;
import com.cloudshield.backend.service.StaleProbeMonitor;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class StaleProbeMonitorTests {
    private final MonitoredResourceRepository resources = mock(MonitoredResourceRepository.class);
    private final ProbeHeartbeatRepository heartbeats = mock(ProbeHeartbeatRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final MonitoredResource resource = new MonitoredResource("host-a", "Host A", "HOST", null, null, "ACTIVE");
    private final StaleProbeMonitor monitor = new StaleProbeMonitor(resources, heartbeats, alerts, 180, 1000);

    @Test void opensAnIncidentForMissingOrUnhealthyHeartbeat() {
        when(resources.count()).thenReturn(1L);
        when(resources.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(resource)));
        when(heartbeats.findFirstByResource_IdOrderByReceivedAtDesc(any())).thenReturn(Optional.empty());
        monitor.checkStaleProbes();
        verify(alerts).evaluateProbeStale(resource);
        verify(alerts, never()).resolveProbeStale(any());
    }

    @Test void healthyRecentHeartbeatResolvesTheStaleIncident() {
        when(resources.count()).thenReturn(1L);
        when(resources.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(resource)));
        when(heartbeats.findFirstByResource_IdOrderByReceivedAtDesc(any()))
                .thenReturn(Optional.of(new ProbeHeartbeat("probe-a", resource, "HEALTHY", "ok", Instant.now())));
        monitor.checkStaleProbes();
        verify(alerts).resolveProbeStale(resource);
        verify(alerts, never()).evaluateProbeStale(any());
    }
}
