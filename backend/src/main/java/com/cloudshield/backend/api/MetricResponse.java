package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.MetricSample;
import java.time.Instant;
import java.util.UUID;

public record MetricResponse(UUID id, UUID resourceId, String metricType, double value, String unit, Instant collectedAt) {
    public static MetricResponse from(MetricSample m) { return new MetricResponse(m.getId(), m.getResource().getId(), m.getMetricType(), m.getValue(), m.getUnit(), m.getCollectedAt()); }
}
