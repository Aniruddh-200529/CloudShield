package com.cloudshield.probe;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class ProbeHeartbeat implements CommandLineRunner {

    private final RestClient restClient = RestClient.create();

    @Override
    public void run(String... args) {

        String response = restClient
                .get()
                .uri("http://localhost:8080/api/probe/heartbeat")
                .retrieve()
                .body(String.class);

        System.out.println("Backend response: " + response);
    }
}