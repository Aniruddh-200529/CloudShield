package com.cloudshield.backend.service;

import com.cloudshield.backend.api.MetricRequest;
import com.cloudshield.backend.domain.MetricSample;
import com.cloudshield.backend.repository.MetricSampleRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MetricService {
    private final MetricSampleRepository metrics;
    private final ResourceService resources;
    public MetricService(MetricSampleRepository metrics, ResourceService resources) { this.metrics = metrics; this.resources = resources; }
    @Transactional public MetricSample record(UUID resourceId, MetricRequest request) {
        if (!Double.isFinite(request.value())) throw new IllegalArgumentException("Metric value must be finite");
        return metrics.save(new MetricSample(resources.get(resourceId), request.metricType(), request.value(), request.unit(), request.collectedAt() == null ? Instant.now() : request.collectedAt()));
    }
    @Transactional(readOnly = true) public List<MetricSample> history(UUID resourceId, Instant from, Instant to, int limit) {
        resources.get(resourceId);
        if (from != null && to != null && from.isAfter(to)) throw new IllegalArgumentException("from must be less than or equal to to");
        return metrics.findHistory(resourceId, from, to, PageRequest.of(0, limit));
    }
}
