package com.cloudshield.probe;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;

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
            try {
                submitter.heartbeat(settings.probeIdentifier(), settings.resourceIdentifier(), "HEALTHY", "Collection completed; metrics=" + observations.size());
            } catch (RuntimeException ex) {
                LOG.warning(failureSummary("heartbeat", ex));
                return;
            }
            try {
                submitter.metrics(settings.probeIdentifier(), settings.resourceIdentifier(), observations);
            } catch (RuntimeException ex) {
                LOG.warning(failureSummary("metrics", ex));
                return;
            }
            LOG.info("Probe telemetry submitted: observations=" + observations.size());
        } catch (RuntimeException ex) {
            LOG.warning(failureSummary("collection", ex));
        } finally { running.set(false); }
    }

    static String failureSummary(String operation, RuntimeException error) {
        String cause;
        if (error instanceof RestClientResponseException response) {
            int status = response.getStatusCode().value();
            if (status == 400) cause = "HTTP 400 telemetry validation failure";
            else if (status == 404 && response.getResponseBodyAsString().matches("(?s).*\\\"message\\\"\\s*:\\s*\\\"Resource not found\\\".*")) cause = "HTTP 404 unknown resource";
            else if (status == 404) cause = "HTTP 404 endpoint not found";
            else cause = "HTTP " + status;
        } else if (error instanceof ResourceAccessException) {
            cause = "connection or timeout failure";
        } else {
            cause = error.getClass().getSimpleName();
        }
        return "Probe " + operation + " submission failed (" + cause + "); the next scheduled collection will retry";
    }
}
