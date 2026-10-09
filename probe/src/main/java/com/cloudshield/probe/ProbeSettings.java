package com.cloudshield.probe;

import java.net.URI;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public record ProbeSettings(String backendUrl, String probeIdentifier, String resourceIdentifier, String apiKey, long intervalMs) {
    public ProbeSettings(@Value("${cloudshield.backend.url:http://localhost:8080}") String backendUrl,
            @Value("${cloudshield.probe.identifier:local-probe}") String probeIdentifier,
            @Value("${cloudshield.probe.resource-identifier:local-probe-resource}") String resourceIdentifier,
            @Value("${cloudshield.probe.api-key:}") String apiKey,
            @Value("${cloudshield.probe.collection-interval-ms:30000}") long intervalMs) {
        URI uri;
        try { uri = URI.create(backendUrl); } catch (RuntimeException ex) { throw new IllegalArgumentException("Probe backend URL must be a valid HTTP(S) origin"); }
        if ((!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || (uri.getPath() != null && !uri.getPath().isBlank() && !"/".equals(uri.getPath()))) throw new IllegalArgumentException("Probe backend URL must be an HTTP(S) origin without embedded credentials or query data");
        probeIdentifier = probeIdentifier == null ? null : probeIdentifier.trim();
        resourceIdentifier = resourceIdentifier == null ? null : resourceIdentifier.trim();
        if (probeIdentifier == null || probeIdentifier.isBlank() || probeIdentifier.length() > 160) throw new IllegalArgumentException("Probe identifier must contain 1 to 160 characters");
        if (resourceIdentifier == null || resourceIdentifier.isBlank() || resourceIdentifier.length() > 160) throw new IllegalArgumentException("Resource identifier must contain 1 to 160 characters");
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("PROBE_API_KEY is required to submit telemetry");
        if (apiKey.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("PROBE_API_KEY cannot contain control characters");
        if (intervalMs < 1000 || intervalMs > Duration.ofHours(1).toMillis()) throw new IllegalArgumentException("Probe collection interval must be between 1000 and 3600000 milliseconds");
        this.backendUrl = backendUrl.replaceAll("/+$", ""); this.probeIdentifier = probeIdentifier; this.resourceIdentifier = resourceIdentifier; this.apiKey = apiKey; this.intervalMs = intervalMs;
    }
}
