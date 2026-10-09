package com.cloudshield.probe;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "cloudshield.probe.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
public class ProbeHeartbeat implements CommandLineRunner {
    private static final Logger LOG = Logger.getLogger(ProbeHeartbeat.class.getName());
    private final SystemMetricCollector collector;
    private final TelemetrySubmitter submitter;
    private final ProbeSettings settings;
    private final AtomicBoolean running = new AtomicBoolean();
    public ProbeHeartbeat(SystemMetricCollector collector, TelemetrySubmitter submitter, ProbeSettings settings) { this.collector = collector; this.submitter = submitter; this.settings = settings; }
    @Override public void run(String... args) { collectAndSend(); }
    @Scheduled(fixedDelayString = "${cloudshield.probe.collection-interval-ms:30000}")
    public void scheduledCollection() { collectAndSend(); }
    void collectAndSend() {
        if (!running.compareAndSet(false, true)) return;
        try {
            var observations = collector.collect();
            submitter.heartbeat(settings.probeIdentifier(), settings.resourceIdentifier(), "HEALTHY", "Collection completed; metrics=" + observations.size());
            submitter.metrics(settings.probeIdentifier(), settings.resourceIdentifier(), observations);
            LOG.info("Probe telemetry submitted: observations=" + observations.size());
        } catch (RuntimeException ex) {
            LOG.log(Level.WARNING, "Probe telemetry submission failed; the next scheduled collection will retry");
        } finally { running.set(false); }
    }
}
