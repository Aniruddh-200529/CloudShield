package com.cloudshield.probe;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class BackendTelemetryClient implements TelemetrySubmitter {
    private static final Logger LOG = Logger.getLogger(BackendTelemetryClient.class.getName());
    private final RestClient client;
    private final ProbeSettings settings;
    @Autowired public BackendTelemetryClient(ProbeSettings settings) {
        this.settings = settings;
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(3));
        this.client = RestClient.builder().requestFactory(factory).build();
    }
    BackendTelemetryClient(ProbeSettings settings, RestClient.Builder builder) {
        this.settings = settings;
        this.client = builder.build();
    }
    @Override public void heartbeat(String probeIdentifier, String resourceIdentifier, String status, String message) {
        client.post().uri(settings.backendUrl() + "/api/probe/heartbeat").header("X-Probe-Key", settings.apiKey())
                .body(Map.of("probeIdentifier", probeIdentifier, "resourceIdentifier", resourceIdentifier, "status", status, "healthMessage", message))
                .retrieve().toBodilessEntity();
    }
    @Override public void metrics(String probeIdentifier, String resourceIdentifier, List<MetricObservation> observations) {
        if (observations.isEmpty()) return;
        var mapped = observations.stream().map(o -> Map.of("observationId", o.observationId().toString(), "metricType", o.metricType(), "value", o.value(), "unit", o.unit(), "collectedAt", o.collectedAt().toString())).toList();
        IngestionResult result = client.post().uri(settings.backendUrl() + "/api/probe/metrics").header("X-Probe-Key", settings.apiKey())
                .body(Map.of("probeIdentifier", probeIdentifier, "resourceIdentifier", resourceIdentifier, "observations", mapped))
                .retrieve().body(IngestionResult.class);
        if (result == null) LOG.warning("Telemetry endpoint returned no ingestion summary");
        else LOG.info("Backend telemetry result: accepted=" + result.accepted() + ", duplicates=" + result.duplicates());
    }
    private record IngestionResult(int accepted, int duplicates) {}
}
