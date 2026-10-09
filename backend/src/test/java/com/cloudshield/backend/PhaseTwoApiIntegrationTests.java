package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
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
    private static final String TEST_ADMIN_PASSWORD = UUID.randomUUID().toString();
    private static final String TEST_PROBE_KEY = UUID.randomUUID().toString();

    @Autowired Environment environment;
    @Autowired ObjectMapper mapper;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
    private String csrfToken;

    @org.springframework.test.context.DynamicPropertySource
    static void databaseProperties(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("cloudshield.bootstrap-admin.username", () -> "integration-admin");
        properties.add("cloudshield.bootstrap-admin.password", () -> TEST_ADMIN_PASSWORD);
        properties.add("cloudshield.probe.api-key", () -> TEST_PROBE_KEY);
    }

    @BeforeEach void authenticateAdmin() throws Exception {
        cookies.getCookieStore().removeAll();
        signIn("integration-admin", TEST_ADMIN_PASSWORD);
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

    @Test
    void enforcesAuthenticationAndRoleBoundariesWhileKeepingLivenessPublic() throws Exception {
        assertThat(get("/api/health").statusCode()).isEqualTo(200);
        assertThat(get("/api/probe/heartbeat").statusCode()).isEqualTo(200);
        cookies.getCookieStore().removeAll();
        assertThat(get("/api/resources").statusCode()).isEqualTo(401);
        signIn("integration-admin", TEST_ADMIN_PASSWORD);
        String viewer = "viewer-" + UUID.randomUUID();
        String viewerPassword = UUID.randomUUID().toString();
        assertThat(post("/api/admin/users", mapper.writeValueAsString(java.util.Map.of(
                "username", viewer, "displayName", "Read only", "password", viewerPassword, "role", "VIEWER"))).statusCode()).isEqualTo(201);
        assertThat(get("/api/admin/users").body()).contains(viewer);
        HttpResponse<String> resource = post("/api/resources", """
                {"resourceIdentifier":"viewer-%s","name":"Viewer test","resourceType":"VM","status":"ACTIVE"}
                """.formatted(UUID.randomUUID()));
        String resourceId = mapper.readTree(resource.body()).get("id").asText();
        assertThat(post("/api/resources/" + resourceId + "/metrics", "{\"metricType\":\"cpu\",\"value\":10}").statusCode()).isEqualTo(201);
        assertThat(post("/api/alerts", "{\"alertType\":\"TEST\",\"severity\":\"LOW\",\"description\":\"Viewer alert check\"}").statusCode()).isEqualTo(201);
        assertThat(post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
        cookies.getCookieStore().removeAll();
        signIn(viewer, viewerPassword);
        assertThat(get("/api/resources").statusCode()).isEqualTo(200);
        assertThat(get("/api/resources/" + resourceId + "/metrics").statusCode()).isEqualTo(200);
        assertThat(get("/api/alerts").statusCode()).isEqualTo(200);
        assertThat(get("/api/audit-events").statusCode()).isEqualTo(403);
        assertThat(get("/api/admin/users").statusCode()).isEqualTo(403);
        assertThat(post("/api/resources", "{}").statusCode()).isEqualTo(403);
        assertThat(post("/api/alerts", "{}").statusCode()).isEqualTo(403);
        assertThat(post("/api/resources/" + resourceId + "/metrics", "{}").statusCode()).isEqualTo(403);
    }

    @Test
    void loginCurrentUserSessionCookiesCsrfAndLogoutFollowBrowserSecurityRules() throws Exception {
        JsonNode current = mapper.readTree(get("/api/auth/me").body());
        assertThat(current.get("username").asText()).isEqualTo("integration-admin");
        assertThat(current.get("role").asText()).isEqualTo("ADMIN");
        assertThat(cookies.getCookieStore().getCookies().stream().map(java.net.HttpCookie::getName))
                .contains("XSRF-TOKEN", "JSESSIONID");

        HttpResponse<String> response = postWithoutCsrf("/api/resources", "{}");
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(post("/api/resources", "{}").statusCode()).isEqualTo(400);

        assertThat(post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
        HttpResponse<String> login = signIn("integration-admin", TEST_ADMIN_PASSWORD);
        assertThat(login.statusCode()).isEqualTo(200);
        String sessionCookie = String.join(";", login.headers().allValues("set-cookie"));
        assertThat(sessionCookie).contains("HttpOnly").contains("SameSite=Lax");
    }

    @Test
    void rejectsInvalidLoginAndRecordsAuthenticationAuditEvents() throws Exception {
        cookies.getCookieStore().removeAll();
        refreshCsrf();
        HttpResponse<String> failed = post("/api/auth/login", mapper.writeValueAsString(java.util.Map.of("username", "integration-admin", "password", UUID.randomUUID().toString())));
        assertThat(failed.statusCode()).isEqualTo(401);
        assertThat(failed.body()).contains("Invalid username or password");
        assertThat(get("/api/auth/me").statusCode()).isEqualTo(401);
        signIn("integration-admin", TEST_ADMIN_PASSWORD);
        String auditRecords = get("/api/audit-events?limit=500").body();
        assertThat(auditRecords).contains("LOGIN_FAILURE").contains("LOGIN_SUCCESS");
        assertThat(post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
        cookies.getCookieStore().removeAll();
        signIn("integration-admin", TEST_ADMIN_PASSWORD);
        assertThat(get("/api/audit-events?limit=500").body()).contains("LOGOUT");
    }

    @Test
    void enforcesDevopsPermissionsAndProtectsTheLastAdministrator() throws Exception {
        JsonNode self = mapper.readTree(get("/api/auth/me").body());
        assertThat(patch("/api/admin/users/" + self.get("id").asText() + "/role", "{\"role\":\"VIEWER\"}").statusCode()).isEqualTo(409);
        String devops = "devops-" + UUID.randomUUID();
        String devopsPassword = UUID.randomUUID().toString();
        assertThat(post("/api/admin/users", mapper.writeValueAsString(java.util.Map.of(
                "username", devops, "displayName", "Operations", "password", devopsPassword, "role", "DEVOPS"))).statusCode()).isEqualTo(201);
        assertThat(get("/api/audit-events").body()).contains("USER_CREATED");
        assertThat(post("/api/auth/logout", "{}").statusCode()).isEqualTo(204);
        cookies.getCookieStore().removeAll(); signIn(devops, devopsPassword);
        assertThat(get("/api/resources").statusCode()).isEqualTo(200);
        assertThat(get("/api/audit-events").statusCode()).isEqualTo(200);
        assertThat(get("/api/admin/users").statusCode()).isEqualTo(403);
        assertThat(post("/api/audit-events", "{}").statusCode()).isEqualTo(403);
        HttpResponse<String> created = post("/api/resources", """
                {"resourceIdentifier":"devops-%s","name":"DevOps resource","resourceType":"VM","status":"ACTIVE"}
                """.formatted(UUID.randomUUID()));
        assertThat(created.statusCode()).isEqualTo(201);
        String resourceId = mapper.readTree(created.body()).get("id").asText();
        assertThat(post("/api/resources/" + resourceId + "/metrics", "{\"metricType\":\"cpu\",\"value\":35}").statusCode()).isEqualTo(201);
        HttpResponse<String> alert = post("/api/alerts", "{\"alertType\":\"DEVOPS_TEST\",\"severity\":\"MEDIUM\",\"description\":\"DevOps lifecycle check\"}");
        assertThat(alert.statusCode()).isEqualTo(201);
        String alertId = mapper.readTree(alert.body()).get("id").asText();
        assertThat(patch("/api/alerts/" + alertId + "/status", "{\"status\":\"ACKNOWLEDGED\"}").statusCode()).isEqualTo(200);
        assertThat(delete("/api/resources/" + resourceId).statusCode()).isEqualTo(403);
    }

    @Test
    void probeKeyAllowsOnlyHeartbeatSubmissionWithoutGrantingUserAccess() throws Exception {
        HttpClient serviceClient = HttpClient.newHttpClient();
        HttpResponse<String> accepted = serviceClient.send(HttpRequest.newBuilder(uri("/api/probe/heartbeat"))
                .header("Content-Type", "application/json").header("X-Probe-Key", TEST_PROBE_KEY)
                .POST(HttpRequest.BodyPublishers.ofString("{\"probeIdentifier\":\"service-only\",\"status\":\"HEALTHY\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(accepted.statusCode()).isEqualTo(201);
        HttpResponse<String> rejected = serviceClient.send(HttpRequest.newBuilder(uri("/api/probe/heartbeat"))
                .header("Content-Type", "application/json").header("X-Probe-Key", UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString("{\"probeIdentifier\":\"service-only\",\"status\":\"HEALTHY\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(rejected.statusCode()).isEqualTo(403);
        assertThat(serviceClient.send(HttpRequest.newBuilder(uri("/api/probe/heartbeats")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
        assertThat(serviceClient.send(HttpRequest.newBuilder(uri("/api/resources")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(401);
    }

    private void refreshCsrf() throws Exception {
        HttpResponse<String> response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        csrfToken = mapper.readTree(response.body()).get("token").asText();
    }
    private HttpResponse<String> signIn(String user, String password) throws Exception {
        refreshCsrf();
        HttpResponse<String> response = post("/api/auth/login", mapper.writeValueAsString(java.util.Map.of("username", user, "password", password)));
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        refreshCsrf();
        return response;
    }
    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken).header("X-Probe-Key", TEST_PROBE_KEY).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postWithoutCsrf(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> patch(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken).method("PATCH", HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> put(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json").header("X-XSRF-TOKEN", csrfToken).PUT(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> delete(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).header("X-XSRF-TOKEN", csrfToken).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }
    private URI uri(String path) { return URI.create("http://localhost:" + environment.getProperty("local.server.port") + path); }
}
