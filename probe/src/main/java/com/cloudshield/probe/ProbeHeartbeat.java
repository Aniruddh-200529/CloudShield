package com.cloudshield.probe;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@ConditionalOnProperty(name = "cloudshield.probe.heartbeat.enabled", havingValue = "true", matchIfMissing = true)
public class ProbeHeartbeat implements CommandLineRunner {

    private final RestClient restClient;
    private final String backendUrl;
    private final String probeIdentifier;

    public ProbeHeartbeat(@Value("${cloudshield.backend.url:http://localhost:8080}") String backendUrl,
            @Value("${cloudshield.probe.identifier:local-probe}") String probeIdentifier) {
        this.restClient = RestClient.create();
        this.backendUrl = backendUrl;
        this.probeIdentifier = probeIdentifier;
    }

    @Override
    public void run(String... args) {

        String response = restClient
                .post()
                .uri(backendUrl + "/api/probe/heartbeat")
                .body(java.util.Map.of("probeIdentifier", probeIdentifier, "status", "HEALTHY",
                        "healthMessage", "Probe process started"))
                .retrieve()
                .body(String.class);

        System.out.println("Probe heartbeat recorded: " + (response != null));
    }
}
