package com.cloudshield.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SystemMetricCollectorTests {
    @Test void collectsOnlyFiniteNonNegativeMetricsWithStableNamesAndUnits() {
        List<MetricObservation> samples = new SystemMetricCollector().collect();
        assertThat(samples).allSatisfy(sample -> {
            assertThat(sample.observationId()).isNotNull(); assertThat(sample.collectedAt()).isNotNull();
            assertThat(sample.value()).isFinite().isNotNegative(); assertThat(sample.metricType()).isNotBlank(); assertThat(sample.unit()).isNotBlank();
        });
        assertThat(samples).allMatch(sample -> List.of("cpu.utilization", "memory.utilization", "memory.available", "disk.utilization", "disk.available", "network.rx.bytes", "network.tx.bytes").contains(sample.metricType()));
    }

    @Test void sendsHeartbeatAndCollectedTelemetryWithoutWaitingForScheduler() {
        var collector = mock(SystemMetricCollector.class); var submitter = mock(TelemetrySubmitter.class);
        var observation = new MetricObservation(java.util.UUID.randomUUID(), "memory.available", 1024, "bytes", java.time.Instant.now());
        when(collector.collect()).thenReturn(List.of(observation));
        var settings = new ProbeSettings("http://localhost:8080", "probe-a", "host-a", "test-key", 30000);
        var runner = new ProbeHeartbeat(collector, submitter, settings);
        runner.collectAndSend();
        verify(submitter).heartbeat("probe-a", "host-a", "HEALTHY", "Collection completed; metrics=1");
        verify(submitter).metrics("probe-a", "host-a", List.of(observation));
    }

    @Test void rejectsMissingCredentialAndUnsafeIntervals() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProbeSettings("http://localhost:8080", "probe", "host", "", 30000))
                .hasMessageContaining("PROBE_API_KEY");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new ProbeSettings("http://localhost:8080", "probe", "host", "key", 10))
                .hasMessageContaining("interval");
    }
}
