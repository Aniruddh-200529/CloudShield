package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cloudshield.backend.api.ProbeMetricRequest;
import com.cloudshield.backend.domain.MetricSample;
import com.cloudshield.backend.domain.MonitoredResource;
import com.cloudshield.backend.repository.MetricSampleRepository;
import com.cloudshield.backend.repository.MonitoredResourceRepository;
import com.cloudshield.backend.service.AlertService;
import com.cloudshield.backend.service.ProbeTelemetryService;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;

class ProbeTelemetryServiceTests {
    private final MetricSampleRepository metrics = mock(MetricSampleRepository.class);
    private final MonitoredResourceRepository resources = mock(MonitoredResourceRepository.class);
    private final AlertService alerts = mock(AlertService.class);
    private final MonitoredResource resource = new MonitoredResource("host-a", "Host A", "HOST", null, "test", "ACTIVE");
    private final ProbeTelemetryService service = new ProbeTelemetryService(metrics, resources, alerts, 85, 90, 90, 2, 300);

    @Test void acceptsNormalizedValidObservationsAndEvaluatesRealThresholdSamples() {
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        when(resources.lockByIdentifier("host-a")).thenReturn(Optional.of(resource));
        when(metrics.existsByProbeIdentifierAndObservationId("probe-a", id)).thenReturn(false);
        when(metrics.findFirstByResource_IdAndMetricTypeOrderByCollectedAtDesc(any(), eq("cpu.utilization"))).thenReturn(Optional.empty());
        when(metrics.save(any())).thenAnswer(call -> call.getArgument(0));
        when(metrics.recentForEvaluation(any(), eq("cpu.utilization"), any(), any(PageRequest.class))).thenReturn(List.of(
                new MetricSample(resource, "cpu.utilization", 92, "%", now), new MetricSample(resource, "cpu.utilization", 91, "%", now.minusSeconds(1))));
        var request = new ProbeMetricRequest(" probe-a ", " host-a ", List.of(new ProbeMetricRequest.Observation(id, "CPU_UTILIZATION", 92d, "percent", now)));
        var response = service.ingest(request);
        assertThat(response.accepted()).isEqualTo(1); assertThat(response.duplicates()).isZero();
        verify(metrics).save(argThat(sample -> sample.getMetricType().equals("cpu.utilization") && sample.getUnit().equals("%") && sample.getProbeIdentifier().equals("probe-a")));
        verify(alerts).evaluateMetric(eq(resource), eq("cpu.utilization"), any(), eq(85d), eq("HIGH"), eq(2));
    }

    @Test void duplicateObservationIsIdempotentlyIgnored() {
        UUID id = UUID.randomUUID();
        when(resources.lockByIdentifier("host-a")).thenReturn(Optional.of(resource));
        when(metrics.existsByProbeIdentifierAndObservationId("probe-a", id)).thenReturn(true);
        var result = service.ingest(new ProbeMetricRequest("probe-a", "host-a", List.of(new ProbeMetricRequest.Observation(id, "cpu.utilization", 20d, "%", Instant.now()))));
        assertThat(result.accepted()).isZero(); assertThat(result.duplicates()).isEqualTo(1);
        verify(metrics, never()).save(any()); verify(alerts, never()).evaluateMetric(any(), any(), any(), anyDouble(), any(), anyInt());
    }

    @Test void rejectsUnsupportedUnitsRangesAndOutOfOrderObservations() {
        UUID id = UUID.randomUUID(); Instant now = Instant.now();
        when(resources.lockByIdentifier("host-a")).thenReturn(Optional.of(resource));
        var badUnit = new ProbeMetricRequest("probe-a", "host-a", List.of(new ProbeMetricRequest.Observation(id, "cpu.utilization", 50d, "bytes", now)));
        assertThatThrownBy(() -> service.ingest(badUnit)).isInstanceOf(IllegalArgumentException.class);
        var badRange = new ProbeMetricRequest("probe-a", "host-a", List.of(new ProbeMetricRequest.Observation(id, "cpu.utilization", 101d, "%", now)));
        assertThatThrownBy(() -> service.ingest(badRange)).isInstanceOf(IllegalArgumentException.class);
        when(metrics.existsByProbeIdentifierAndObservationId("probe-a", id)).thenReturn(false);
        when(metrics.findFirstByResource_IdAndMetricTypeOrderByCollectedAtDesc(any(), eq("cpu.utilization")))
                .thenReturn(Optional.of(new MetricSample(resource, "cpu.utilization", 30, "%", now)));
        var late = new ProbeMetricRequest("probe-a", "host-a", List.of(new ProbeMetricRequest.Observation(id, "cpu.utilization", 25d, "%", now.minusSeconds(1))));
        assertThatThrownBy(() -> service.ingest(late)).hasMessageContaining("Out-of-order");
    }
}
