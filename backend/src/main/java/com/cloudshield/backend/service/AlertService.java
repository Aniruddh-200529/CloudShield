package com.cloudshield.backend.service;

import com.cloudshield.backend.api.AlertRequest;
import com.cloudshield.backend.domain.AlertRecord;
import com.cloudshield.backend.repository.AlertRecordRepository;
import com.cloudshield.backend.repository.AlertStatusHistoryRepository;
import com.cloudshield.backend.api.AuditEventRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AlertService {
    private final AlertRecordRepository repository;
    private final ResourceService resources;
    private final AlertStatusHistoryRepository history;
    private final AuditEventService audit;
    private final com.cloudshield.backend.repository.ProbeHeartbeatRepository heartbeats;
    private final long staleSeconds;
    public AlertService(AlertRecordRepository repository, ResourceService resources, AlertStatusHistoryRepository history, AuditEventService audit,
            com.cloudshield.backend.repository.ProbeHeartbeatRepository heartbeats,
            @org.springframework.beans.factory.annotation.Value("${cloudshield.probe.stale-after-seconds:180}") long staleSeconds) {
        this.repository = repository; this.resources = resources; this.history = history; this.audit = audit; this.heartbeats = heartbeats; this.staleSeconds = staleSeconds;
    }
    @Transactional public AlertRecord create(AlertRequest request) { return create(request, "unknown"); }
    @Transactional public AlertRecord create(AlertRequest request, String actor) {
        var resource = request.resourceId() == null ? null : resources.get(request.resourceId());
        var alert = repository.save(new AlertRecord(request.alertType(), request.severity(), request.description(), resource, "OPEN"));
        history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, null, "OPEN", actor));
        audit.record(new AuditEventRequest("ALERT_CREATED", actor, alert.getId().toString(), resource == null ? null : resource.getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "severity", alert.getSeverity())));
        return alert;
    }
    @Transactional(readOnly = true) public List<AlertRecord> list(String status, int limit) { return list(status, 0, limit); }
    @Transactional(readOnly = true) public List<AlertRecord> list(String status, int page, int limit) { return status == null ? repository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, limit)) : repository.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(page, limit)); }
    @Transactional(readOnly = true) public AlertRecord get(UUID id) { return repository.findById(id).orElseThrow(() -> new NotFoundException("Alert not found")); }
    @Transactional(readOnly = true) public List<com.cloudshield.backend.domain.AlertStatusHistory> history(UUID id, int limit) {
        get(id); return history.findByAlert_IdOrderByChangedAtDesc(id, PageRequest.of(0, limit));
    }
    @Transactional public AlertRecord updateStatus(UUID id, String status, String actor) {
        AlertRecord alert = repository.findById(id).orElseThrow(() -> new NotFoundException("Alert not found"));
        String previous = alert.getStatus();
        alert.changeStatus(status);
        if (!previous.equals(alert.getStatus())) {
            history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, previous, alert.getStatus(), actor));
            audit.record(new AuditEventRequest("ALERT_STATUS_CHANGED", actor, id.toString(), alert.getResource() == null ? null : alert.getResource().getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("from", previous, "to", alert.getStatus(), "alertType", alert.getAlertType())));
        }
        return alert;
    }
    @Transactional public void evaluateMetric(com.cloudshield.backend.domain.MonitoredResource resource, String metricType,
            java.util.List<com.cloudshield.backend.domain.MetricSample> recent, double threshold, String severity, int consecutive) {
        String dedup = "METRIC:" + resource.getId() + ":" + metricType;
        boolean breached = recent.size() >= consecutive && recent.subList(0, consecutive).stream().allMatch(m -> m.getValue() >= threshold);
        var active = repository.findFirstByDeduplicationKeyAndStatusIn(dedup, java.util.List.of("OPEN", "ACKNOWLEDGED"));
        if (breached && active.isEmpty()) {
            var alert = repository.save(new AlertRecord("METRIC_THRESHOLD", severity,
                    metricType + " exceeded " + threshold + " for " + consecutive + " consecutive samples", resource, "OPEN", dedup));
            history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, null, "OPEN", "system:event-engine"));
            audit.record(new AuditEventRequest("ALERT_CREATED", "system:event-engine", alert.getId().toString(), resource.getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "metricType", metricType, "severity", severity)));
        } else if (!breached && !recent.isEmpty() && recent.get(0).getValue() < threshold && active.isPresent()) {
            var alert = active.get();
            String previous = alert.getStatus();
            alert.changeStatus("RESOLVED");
            history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, previous, "RESOLVED", "system:event-engine"));
            audit.record(new AuditEventRequest("ALERT_AUTO_RESOLVED", "system:event-engine", alert.getId().toString(), resource.getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "metricType", metricType, "reason", "metric recovered")));
        }
    }
    @Transactional public void evaluateProbeStale(com.cloudshield.backend.domain.MonitoredResource resource) {
        resource = resources.lock(resource.getId());
        var latest = heartbeats.findFirstByResource_IdOrderByReceivedAtDesc(resource.getId());
        if (latest.isPresent() && latest.get().getReceivedAt().isAfter(java.time.Instant.now().minusSeconds(staleSeconds)) && "HEALTHY".equals(latest.get().getStatus())) {
            resolveProbeStaleLocked(resource);
            return;
        }
        String dedup = "PROBE_STALE:" + resource.getId();
        if (repository.findFirstByDeduplicationKeyAndStatusIn(dedup, java.util.List.of("OPEN", "ACKNOWLEDGED")).isEmpty()) {
            var alert = repository.save(new AlertRecord("PROBE_STALE", "HIGH", "Probe heartbeat is missing, stale, or reports an unhealthy status", resource, "OPEN", dedup));
            history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, null, "OPEN", "system:stale-probe-check"));
            audit.record(new AuditEventRequest("ALERT_CREATED", "system:stale-probe-check", alert.getId().toString(), resource.getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "severity", alert.getSeverity())));
        }
    }
    @Transactional public void resolveProbeStale(com.cloudshield.backend.domain.MonitoredResource resource) {
        resource = resources.lock(resource.getId());
        resolveProbeStaleLocked(resource);
    }
    private void resolveProbeStaleLocked(com.cloudshield.backend.domain.MonitoredResource resource) {
        repository.findFirstByDeduplicationKeyAndStatusIn("PROBE_STALE:" + resource.getId(), java.util.List.of("OPEN", "ACKNOWLEDGED"))
                .ifPresent(alert -> { String previous = alert.getStatus(); alert.changeStatus("RESOLVED"); history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, previous, "RESOLVED", "system:heartbeat")); audit.record(new AuditEventRequest("ALERT_AUTO_RESOLVED", "system:heartbeat", alert.getId().toString(), resource.getId(), java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "reason", "probe heartbeat recovered"))); });
    }
    @Transactional public void evaluateAuthenticationFailureBurst(String source) {
        String key = "AUTH_FAILURE_BURST:" + shortHash(source == null ? "unknown" : source);
        if (repository.findFirstByDeduplicationKeyAndStatusIn(key, java.util.List.of("OPEN", "ACKNOWLEDGED")).isEmpty()) {
            var alert = repository.save(new AlertRecord("AUTH_FAILURE_BURST", "MEDIUM",
                    "Repeated failed sign-in attempts from one source; suspicious activity indicator, not a confirmed attack", null, "OPEN", key));
            history.save(new com.cloudshield.backend.domain.AlertStatusHistory(alert, null, "OPEN", "system:security-monitor"));
            audit.record(new AuditEventRequest("SECURITY_ALERT_CREATED", "system:security-monitor", alert.getId().toString(), null, java.time.Instant.now(), "SUCCESS", java.util.Map.of("alertType", alert.getAlertType(), "severity", alert.getSeverity(), "indicator", "repeated-login-failures")));
        }
    }
    private static String shortHash(String value) {
        try { byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest, 0, 16);
        } catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 is unavailable", ex); }
    }
}
