package com.cloudshield.backend.service;

import com.cloudshield.backend.api.AuditEventRequest;
import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.repository.AuditEventRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditEventService {
    private final AuditEventRepository repository;
    private final ResourceService resources;
    public AuditEventService(AuditEventRepository repository, ResourceService resources) { this.repository = repository; this.resources = resources; }
    @Transactional public AuditEvent record(AuditEventRequest request) {
        if (containsSensitiveField(request.details())) throw new IllegalArgumentException("Audit details cannot contain sensitive fields");
        return repository.save(new AuditEvent(request.eventType(), request.actorIdentifier(), request.targetIdentifier(), request.resourceId() == null ? null : resources.get(request.resourceId()), request.occurredAt() == null ? Instant.now() : request.occurredAt(), request.outcome(), request.details()));
    }
    @Transactional(readOnly = true) public List<AuditEvent> list(int limit) { return repository.findAllByOrderByOccurredAtDesc(PageRequest.of(0, limit)); }

    private boolean containsSensitiveField(Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                if (entry.getKey() instanceof String key && isSensitiveKey(key)) return true;
                if (containsSensitiveField(entry.getValue())) return true;
            }
        } else if (value instanceof Collection<?> values) {
            for (Object item : values) if (containsSensitiveField(item)) return true;
        }
        return false;
    }

    private boolean isSensitiveKey(String key) {
        String normalized = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return normalized.contains("password") || normalized.contains("passwd") || normalized.contains("secret")
                || normalized.contains("token") || normalized.contains("cookie") || normalized.contains("credential")
                || normalized.contains("authorization") || normalized.contains("apikey") || normalized.contains("accesskey")
                || normalized.contains("sessionid") || normalized.contains("csrf") || normalized.contains("privatekey");
    }
}
