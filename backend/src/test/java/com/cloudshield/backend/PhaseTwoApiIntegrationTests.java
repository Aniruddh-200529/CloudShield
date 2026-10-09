package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
class PhaseTwoApiIntegrationTests {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    private final HttpClient client = HttpClient.newHttpClient();

    @org.springframework.test.context.DynamicPropertySource
    static void databaseProperties(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Test
    void persistsResourcesMetricsHeartbeatsAuditsAndAlertLifecycle() throws Exception {
        String identifier = "test-" + UUID.randomUUID();
        HttpResponse<String> created = post("/api/resources", """
                {"resourceIdentifier":"%s","name":"Test VM","resourceType":"VM","address":"10.0.0.5","environment":"test","status":"ACTIVE"}
                """.formatted(identifier));
        assertThat(created.statusCode()).isEqualTo(201);
        JsonNode resource = mapper.readTree(created.body());
        String resourceId = resource.get("id").asText();
        assertThat(get("/api/resources/" + resourceId).statusCode()).isEqualTo(200);
        assertThat(put("/api/resources/" + resourceId, """
                {"resourceIdentifier":"%s","name":"Test VM updated","resourceType":"VM","environment":"test","status":"DEGRADED"}
                """.formatted(identifier)).statusCode()).isEqualTo(200);

        String oldTime = "2026-01-01T00:00:00Z";
        String newTime = "2026-02-01T00:00:00Z";
        assertThat(post("/api/resources/" + resourceId + "/metrics", """
                {"metricType":"cpu.utilization","value":42.5,"unit":"percent","collectedAt":"%s"}
                """.formatted(oldTime)).statusCode()).isEqualTo(201);
        assertThat(post("/api/resources/" + resourceId + "/metrics", """
                {"metricType":"cpu.utilization","value":60.0,"unit":"percent","collectedAt":"%s"}
                """.formatted(newTime)).statusCode()).isEqualTo(201);
        JsonNode filteredMetrics = mapper.readTree(get("/api/resources/" + resourceId + "/metrics?from=" + newTime + "&to=2026-03-01T00:00:00Z").body());
        assertThat(filteredMetrics).hasSize(1);
        assertThat(filteredMetrics.get(0).get("value").asDouble()).isEqualTo(60.0);
        assertThat(get("/api/resources/" + resourceId + "/metrics?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z").statusCode()).isEqualTo(400);

        assertThat(post("/api/probe/heartbeat", """
                {"probeIdentifier":"probe-test","resourceId":"%s","status":"HEALTHY"}
                """.formatted(resourceId)).statusCode()).isEqualTo(201);
        assertThat(get("/api/probe/heartbeats?probeIdentifier=probe-test").body()).contains(resourceId);
        assertThat(post("/api/audit-events", """
                {"eventType":"RESOURCE_CHECK","actorIdentifier":"operator","resourceId":"%s","outcome":"SUCCESS","details":{"source":"integration-test"}}
                """.formatted(resourceId)).statusCode()).isEqualTo(201);
        assertThat(get("/api/audit-events").body()).contains("integration-test");
        assertThat(post("/api/audit-events", """
                {"eventType":"INVALID_REFERENCE","resourceId":"%s","outcome":"SUCCESS"}
                """.formatted(UUID.randomUUID())).statusCode()).isEqualTo(404);
        assertThat(post("/api/audit-events", "{\"eventType\":\"\",\"outcome\":\"\"}").statusCode()).isEqualTo(400);

        HttpResponse<String> alert = post("/api/alerts", """
                {"alertType":"CAPACITY","severity":"HIGH","description":"Capacity threshold exceeded","resourceId":"%s"}
                """.formatted(resourceId));
        assertThat(alert.statusCode()).isEqualTo(201);
        String alertId = mapper.readTree(alert.body()).get("id").asText();
        assertThat(post("/api/alerts", """
                {"alertType":"BAD_INPUT","severity":"SEVERE","description":"Invalid severity"}
                """).statusCode()).isEqualTo(400);
        JsonNode acknowledged = mapper.readTree(patch("/api/alerts/" + alertId + "/status", "{\"status\":\"ACKNOWLEDGED\"}").body());
        assertThat(acknowledged.get("acknowledgedAt").isNull()).isFalse();
        assertThat(get("/api/alerts?status=ACKNOWLEDGED").body()).contains(alertId);
        JsonNode resolved = mapper.readTree(patch("/api/alerts/" + alertId + "/status", "{\"status\":\"RESOLVED\"}").body());
        assertThat(resolved.get("resolvedAt").isNull()).isFalse();
        assertThat(get("/api/alerts?status=RESOLVED").body()).contains(alertId);
        assertThat(patch("/api/alerts/" + alertId + "/status", "{\"status\":\"ACKNOWLEDGED\"}").statusCode()).isEqualTo(400);
        assertThat(get("/api/alerts?status=CLOSED").statusCode()).isEqualTo(400);

        assertThat(get("/api/health").statusCode()).isEqualTo(200);
        assertThat(get("/api/probe/heartbeat").body()).contains("connection successful");
    }

    @Test
    void rejectsInvalidAndDuplicateResourcesAndReportsMissingRecords() throws Exception {
        String id = "duplicate-" + UUID.randomUUID();
        String valid = """
                {"resourceIdentifier":"%s","name":"VM","resourceType":"VM","status":"ACTIVE"}
                """.formatted(id);
        assertThat(post("/api/resources", "{\"resourceIdentifier\":\"\",\"name\":\"\",\"resourceType\":\"\",\"status\":\"bad\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/resources", valid).statusCode()).isEqualTo(201);
        HttpResponse<String> duplicate = post("/api/resources", valid);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(mapper.readTree(duplicate.body()).get("status").asInt()).isEqualTo(409);
        assertThat(get("/api/resources/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
        assertThat(post("/api/resources/" + UUID.randomUUID() + "/metrics", "{\"metricType\":\"cpu\",\"value\":1}").statusCode()).isEqualTo(404);
        assertThat(get("/api/resources?page=0&size=201").statusCode()).isEqualTo(400);
        assertThat(get("/api/alerts?limit=501").statusCode()).isEqualTo(400);
        assertThat(post("/api/probe/heartbeat", "{\"probeIdentifier\":\"test-probe\",\"status\":\"INVALID\"}").statusCode()).isEqualTo(400);
        assertThat(post("/api/probe/heartbeat", """
                {"probeIdentifier":"test-probe","resourceId":"%s","status":"HEALTHY"}
                """.formatted(UUID.randomUUID())).statusCode()).isEqualTo(404);
    }

    @Test
    void refusesDeletingResourcesWithMetricHistoryAndKeepsTheResource() throws Exception {
        String identifier = "fk-test-" + UUID.randomUUID();
        HttpResponse<String> create = post("/api/resources", """
                {"resourceIdentifier":"%s","name":"FK test VM","resourceType":"VM","status":"ACTIVE"}
                """.formatted(identifier));
        String resourceId = mapper.readTree(create.body()).get("id").asText();
        assertThat(create.statusCode()).isEqualTo(201);
        assertThat(post("/api/resources/" + resourceId + "/metrics", "{\"metricType\":\"test\",\"value\":1}").statusCode()).isEqualTo(201);
        assertThat(delete("/api/resources/" + resourceId).statusCode()).isEqualTo(409);
        assertThat(get("/api/resources/" + resourceId).statusCode()).isEqualTo(200);

        String unusedIdentifier = "delete-test-" + UUID.randomUUID();
        HttpResponse<String> unused = post("/api/resources", """
                {"resourceIdentifier":"%s","name":"Unreferenced VM","resourceType":"VM","status":"ACTIVE"}
                """.formatted(unusedIdentifier));
        String unusedId = mapper.readTree(unused.body()).get("id").asText();
        assertThat(delete("/api/resources/" + unusedId).statusCode()).isEqualTo(204);
        assertThat(get("/api/resources/" + unusedId).statusCode()).isEqualTo(404);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> patch(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> put(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path); }
}
