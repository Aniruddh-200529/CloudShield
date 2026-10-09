package com.cloudshield.backend.service;

import com.cloudshield.backend.repository.MonitoredResourceRepository;
import com.cloudshield.backend.repository.ProbeHeartbeatRepository;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class StaleProbeMonitor {
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(StaleProbeMonitor.class.getName());
    private final MonitoredResourceRepository resources;
    private final ProbeHeartbeatRepository heartbeats;
    private final AlertService alerts;
    private final long staleSeconds;
    private final int maxResourcesPerRun;
    private final java.util.concurrent.atomic.AtomicInteger pageCursor = new java.util.concurrent.atomic.AtomicInteger();
    public StaleProbeMonitor(MonitoredResourceRepository resources, ProbeHeartbeatRepository heartbeats, AlertService alerts,
            @Value("${cloudshield.probe.stale-after-seconds:180}") long staleSeconds,
            @Value("${cloudshield.probe.stale-max-resources-per-run:1000}") int maxResourcesPerRun) {
        if (staleSeconds < 30 || staleSeconds > 86400) throw new IllegalArgumentException("Probe stale timeout must be between 30 and 86400 seconds");
        if (maxResourcesPerRun < 1 || maxResourcesPerRun > 20000) throw new IllegalArgumentException("Maximum stale-check batch must be between 1 and 20000 resources");
        this.resources = resources; this.heartbeats = heartbeats; this.alerts = alerts; this.staleSeconds = staleSeconds; this.maxResourcesPerRun = maxResourcesPerRun;
    }
    @Scheduled(fixedDelayString = "${cloudshield.probe.stale-check-ms:60000}", initialDelayString = "${cloudshield.probe.stale-initial-delay-ms:60000}")
    public void checkStaleProbes() {
        try { runStaleCheck(); }
        catch (RuntimeException ex) { LOG.warning("Stale probe evaluation failed; it will retry on the next scheduled pass"); }
    }
    private void runStaleCheck() {
        Instant cutoff = Instant.now().minusSeconds(staleSeconds);
        long resourceCount = resources.count();
        int pageCount = (int) Math.ceil(resourceCount / 200d);
        if (pageCount == 0) return;
        int pagesToVisit = Math.min(pageCount, Math.max(1, (int) Math.ceil(maxResourcesPerRun / 200d)));
        int startPage = pageCursor.getAndUpdate(current -> (current + pagesToVisit) % pageCount) % pageCount;
        int checked = 0;
        for (int offset = 0; offset < pagesToVisit && checked < maxResourcesPerRun; offset++) {
            var batch = resources.findAll(PageRequest.of((startPage + offset) % pageCount, 200)).getContent();
            for (var resource : batch) {
                if (checked++ >= maxResourcesPerRun) break;
                var latest = heartbeats.findFirstByResource_IdOrderByReceivedAtDesc(resource.getId());
                if (latest.isEmpty() || latest.get().getReceivedAt().isBefore(cutoff) || !"HEALTHY".equals(latest.get().getStatus())) alerts.evaluateProbeStale(resource);
                else alerts.resolveProbeStale(resource);
            }
        }
    }
}
