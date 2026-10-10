package com.cloudshield.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

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

    @Test void reportsFailureStageAndStatusWithoutLoggingResponseDetails() {
        var response = HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                "Resource not found; do-not-log-this".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.StandardCharsets.UTF_8);

        String summary = ProbeHeartbeat.failureSummary("metrics", response);

        assertThat(summary).contains("metrics", "HTTP 404", "next scheduled collection will retry")
                .doesNotContain("Resource not found", "do-not-log-this");
        String unknownResource = ProbeHeartbeat.failureSummary("heartbeat",
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                        "{\"message\":\"Resource not found\",\"detail\":\"secret-detail\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertThat(unknownResource).contains("HTTP 404 unknown resource").doesNotContain("secret-detail", "Resource not found");
        String unmappedRoute = ProbeHeartbeat.failureSummary("heartbeat",
                HttpClientErrorException.create(HttpStatus.NOT_FOUND, "Not Found", null,
                        "{\"error\":\"Not Found\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8));
        assertThat(unmappedRoute).contains("HTTP 404 endpoint not found");
        assertThat(ProbeHeartbeat.failureSummary("metrics",
                HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request", null,
                        "invalid metric".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                        java.nio.charset.StandardCharsets.UTF_8)))
                .contains("HTTP 400 telemetry validation failure").doesNotContain("invalid metric");
    }
}
