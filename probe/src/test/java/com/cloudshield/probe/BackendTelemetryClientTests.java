package com.cloudshield.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

class BackendTelemetryClientTests {
    @Test void submitsMetricBatchWithProbeHeaderAndStableResourceIdentity() {
        String secret = UUID.randomUUID().toString();
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://backend.test/api/probe/metrics")).andExpect(method(HttpMethod.POST)).andExpect(request -> {
            boolean matches = MessageDigest.isEqual(secret.getBytes(StandardCharsets.UTF_8), request.getHeaders().getFirst("X-Probe-Key").getBytes(StandardCharsets.UTF_8));
            if (!matches) throw new AssertionError("Probe credential header did not match the configured test value");
            String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request).getBodyAsString();
            if (!body.contains("probe-test") || !body.contains("host-test") || body.contains(secret)) throw new AssertionError("Telemetry request body did not meet the expected safe shape");
        }).andRespond(withSuccess("{\"accepted\":1,\"duplicates\":0}", MediaType.APPLICATION_JSON));
        var client = new BackendTelemetryClient(new ProbeSettings("http://backend.test", "probe-test", "host-test", secret, 30000), builder);
        client.metrics("probe-test", "host-test", List.of(new MetricObservation(UUID.randomUUID(), "cpu.utilization", 34.5, "%", Instant.now())));
        server.verify();
    }

    @Test void surfacesBackendErrorsSoNextScheduledCycleCanRetry() {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("http://backend.test/api/probe/heartbeat")).andExpect(method(HttpMethod.POST)).andRespond(withServerError());
        var client = new BackendTelemetryClient(new ProbeSettings("http://backend.test", "probe-test", "host-test", "test-only-key", 30000), builder);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.heartbeat("probe-test", "host-test", "HEALTHY", "test"))
                .isInstanceOf(RestClientResponseException.class);
        server.verify();
    }
}
