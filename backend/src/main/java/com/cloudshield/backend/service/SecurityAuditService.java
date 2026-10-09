package com.cloudshield.backend.service;

import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Map;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class SecurityAuditService {
    private final AuditEventRepository events;
    private final AlertService alerts;
    private final int failureThreshold;
    private final ConcurrentHashMap<String, FailureWindow> failures = new ConcurrentHashMap<>();
    public SecurityAuditService(AuditEventRepository events, AlertService alerts,
            @Value("${cloudshield.alerts.login-failure-threshold:10}") int failureThreshold) {
        if (failureThreshold < 2 || failureThreshold > 1000) throw new IllegalArgumentException("Login failure alert threshold must be between 2 and 1000");
        this.events = events; this.alerts = alerts; this.failureThreshold = failureThreshold;
    }
    @Transactional public void record(String event, String actor, String target, String outcome, Map<String, Object> details) {
        events.save(new AuditEvent(event, actor, target, null, Instant.now(), outcome, details));
        if ("LOGIN_FAILURE".equals(event)) {
            String source = String.valueOf(details == null ? "unknown" : details.getOrDefault("source", "unknown"));
            long now = System.currentTimeMillis();
            failures.entrySet().removeIf(entry -> now - entry.getValue().startedAt > Duration.ofMinutes(10).toMillis());
            AtomicBoolean shouldAlert = new AtomicBoolean();
            failures.compute(source, (ignored, current) -> {
                FailureWindow next = current == null ? new FailureWindow(now, 1, false) : new FailureWindow(current.startedAt, current.count + 1, current.alerted);
                if (next.count >= failureThreshold && !next.alerted) { shouldAlert.set(true); next = new FailureWindow(next.startedAt, next.count, true); }
                return next;
            });
            if (shouldAlert.get()) {
                try { alerts.evaluateAuthenticationFailureBurst(source); }
                catch (RuntimeException ex) {
                    failures.computeIfPresent(source, (ignored, current) -> new FailureWindow(current.startedAt, current.count, false));
                    throw ex;
                }
            }
            if (failures.size() > 10000) failures.clear();
        }
    }
    private record FailureWindow(long startedAt, int count, boolean alerted) {}
}
