package com.cloudshield.backend.service;

import com.cloudshield.backend.api.ProbeMetricRequest;
import com.cloudshield.backend.api.ProbeMetricResponse;
import com.cloudshield.backend.domain.MetricSample;
import com.cloudshield.backend.repository.MetricSampleRepository;
import com.cloudshield.backend.repository.MonitoredResourceRepository;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProbeTelemetryService {
    private static final Map<String, String> TYPES = Map.of(
            "cpu.utilization", "%", "memory.utilization", "%", "memory.available", "bytes",
            "disk.utilization", "%", "disk.available", "bytes", "network.rx.bytes", "bytes",
            "network.tx.bytes", "bytes");
    private final MetricSampleRepository metrics;
    private final MonitoredResourceRepository resources;
    private final AlertService alerts;
    private final double cpuThreshold, memoryThreshold, diskThreshold;
    private final int consecutive;
    private final long correlationSeconds;

    public ProbeTelemetryService(MetricSampleRepository metrics, MonitoredResourceRepository resources, AlertService alerts,
            @Value("${cloudshield.alerts.cpu-threshold:85}") double cpuThreshold,
            @Value("${cloudshield.alerts.memory-threshold:90}") double memoryThreshold,
            @Value("${cloudshield.alerts.disk-threshold:90}") double diskThreshold,
            @Value("${cloudshield.alerts.consecutive-samples:2}") int consecutive,
            @Value("${cloudshield.alerts.correlation-window-seconds:300}") long correlationSeconds) {
        this.metrics = metrics; this.resources = resources; this.alerts = alerts;
        this.cpuThreshold = cpuThreshold; this.memoryThreshold = memoryThreshold; this.diskThreshold = diskThreshold;
        this.consecutive = consecutive; this.correlationSeconds = correlationSeconds;
        if (cpuThreshold <= 0 || cpuThreshold > 100 || memoryThreshold <= 0 || memoryThreshold > 100 || diskThreshold <= 0 || diskThreshold > 100 || consecutive < 1 || consecutive > 10 || correlationSeconds < 1 || correlationSeconds > 86400)
            throw new IllegalArgumentException("Alert thresholds and correlation settings are outside their allowed ranges");
    }

    @Transactional
    public ProbeMetricResponse ingest(ProbeMetricRequest request) {
        String probe = request.probeIdentifier().trim();
        var resource = resources.lockByIdentifier(request.resourceIdentifier().trim())
                .orElseThrow(() -> new NotFoundException("Resource not found"));
        int accepted = 0, duplicates = 0;
        var batchIds = new java.util.HashSet<java.util.UUID>();
        for (var observation : request.observations()) {
            String type = normalizeType(observation.metricType());
            String expectedUnit = TYPES.get(type);
            if (expectedUnit == null) throw new IllegalArgumentException("Unsupported metric type: " + type);
            String unit = normalizeUnit(observation.unit());
            if (!expectedUnit.equals(unit)) throw new IllegalArgumentException("Invalid unit for metric type " + type);
            if (observation.value() == null || !Double.isFinite(observation.value()) || observation.value() < 0)
                throw new IllegalArgumentException("Metric values must be finite and non-negative");
            if (type.endsWith(".utilization") && observation.value() > 100)
                throw new IllegalArgumentException("Utilization metrics must be between 0 and 100 percent");
            Instant now = Instant.now();
            if (observation.collectedAt().isAfter(now.plusSeconds(300)) || observation.collectedAt().isBefore(now.minusSeconds(86400)))
                throw new IllegalArgumentException("Metric timestamp is outside the accepted time window");
            if (!batchIds.add(observation.observationId()) || metrics.existsByProbeIdentifierAndObservationId(probe, observation.observationId())) { duplicates++; continue; }
            var previous = metrics.findFirstByResource_IdAndMetricTypeOrderByCollectedAtDesc(resource.getId(), type);
            if (previous.isPresent() && observation.collectedAt().isBefore(previous.get().getCollectedAt()))
                throw new IllegalArgumentException("Out-of-order metric observations are not accepted");
            MetricSample sample = metrics.save(new MetricSample(resource, type, observation.value(), unit, observation.collectedAt(), probe, observation.observationId()));
            accepted++;
            double threshold = switch (type) {
                case "cpu.utilization" -> cpuThreshold;
                case "memory.utilization" -> memoryThreshold;
                case "disk.utilization" -> diskThreshold;
                default -> Double.POSITIVE_INFINITY;
            };
            if (Double.isFinite(threshold) && !observation.collectedAt().isBefore(now.minusSeconds(correlationSeconds))) {
                var recent = metrics.recentForEvaluation(resource.getId(), type,
                        now.minusSeconds(correlationSeconds), PageRequest.of(0, consecutive));
                alerts.evaluateMetric(resource, type, recent, threshold, "HIGH", consecutive);
            }
        }
        return new ProbeMetricResponse(accepted, duplicates);
    }

    static String normalizeType(String type) { return type.trim().toLowerCase(Locale.ROOT).replace('_', '.').replaceAll("\\s+", ""); }
    static String normalizeUnit(String unit) {
        String normalized = unit.trim().toLowerCase(Locale.ROOT);
        if (Set.of("percent", "percentage", "pct").contains(normalized)) return "%";
        if (Set.of("byte", "bytes", "b").contains(normalized)) return "bytes";
        return normalized;
    }
}
