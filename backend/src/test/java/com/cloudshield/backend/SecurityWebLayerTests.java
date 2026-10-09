package com.cloudshield.backend;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.cloudshield.backend.domain.AuditEvent;
import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import com.cloudshield.backend.repository.UserAccountRepository;
import com.cloudshield.backend.security.CurrentUserFilter;
import com.cloudshield.backend.security.DatabaseUserDetailsService;
import com.cloudshield.backend.security.SecurityConfiguration;
import com.cloudshield.backend.security.UserPrincipal;
import com.cloudshield.backend.service.AlertService;
import com.cloudshield.backend.service.AuditEventService;
import com.cloudshield.backend.service.HeartbeatService;
import com.cloudshield.backend.service.LoginAttemptLimiter;
import com.cloudshield.backend.service.MetricService;
import com.cloudshield.backend.service.ResourceService;
import com.cloudshield.backend.service.SecurityAuditService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.MockMvcPrint;
import com.cloudshield.backend.api.*;

@WebMvcTest(controllers = {HealthController.class, ProbeController.class, ResourceController.class,
        ProbeMetricsController.class, MetricController.class, AuditEventController.class, AlertController.class, AuthController.class, MfaController.class, AdminUserController.class})
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfiguration.class, DatabaseUserDetailsService.class, CurrentUserFilter.class, WebConfiguration.class})
class SecurityWebLayerTests {
    private static final String PROBE_KEY = UUID.randomUUID().toString();

    @org.springframework.test.context.DynamicPropertySource
    static void probeKeyProperty(org.springframework.test.context.DynamicPropertyRegistry properties) {
        properties.add("cloudshield.probe.api-key", () -> PROBE_KEY);
    }
    @Autowired MockMvc mvc;
    @MockitoBean AuthenticationManager authenticationManager;
    @MockitoBean UserAccountRepository users;
    @MockitoBean ResourceService resources;
    @MockitoBean MetricService metrics;
    @MockitoBean HeartbeatService heartbeats;
    @MockitoBean com.cloudshield.backend.service.ProbeTelemetryService telemetry;
    @MockitoBean AuditEventService auditEvents;
    @MockitoBean AlertService alerts;
    @MockitoBean LoginAttemptLimiter loginLimiter;
    @MockitoBean SecurityAuditService securityAudit;
    @MockitoBean com.cloudshield.backend.service.MfaService mfaService;
    @MockitoBean com.cloudshield.backend.service.MfaAttemptLimiter mfaAttemptLimiter;

    @Test void unauthenticatedRequestsAreDeniedAndHealthRemainsPublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/probe/heartbeat")).andExpect(status().isOk());
        mvc.perform(get("/api/resources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void securityHeadersAndCsrfCookieArePresentForLocalSessionMode() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Referrer-Policy", "no-referrer"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Permissions-Policy", "camera=(), microphone=(), geolocation=()"));
        MvcResult csrfResult = mvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        var csrfCookie = csrfResult.getResponse().getCookie("XSRF-TOKEN");
        var cookieHeaders = csrfResult.getResponse().getHeaders("Set-Cookie");
        boolean headerCookiePresent = cookieHeaders.stream().anyMatch(value -> value.startsWith("XSRF-TOKEN="));
        org.assertj.core.api.Assertions.assertThat(csrfCookie != null || headerCookiePresent).isTrue();
        if (csrfCookie != null) {
            org.assertj.core.api.Assertions.assertThat(csrfCookie.getPath()).isEqualTo("/");
            org.assertj.core.api.Assertions.assertThat(csrfCookie.isHttpOnly()).isFalse();
            org.assertj.core.api.Assertions.assertThat(csrfCookie.getSecure()).isFalse();
            org.assertj.core.api.Assertions.assertThat(csrfCookie.getAttribute("SameSite")).isEqualTo("Lax");
        }
        if (csrfCookie == null && headerCookiePresent) {
            String csrfHeader = cookieHeaders.stream().filter(value -> value.startsWith("XSRF-TOKEN=")).findFirst().orElseThrow();
            String normalizedCookie = csrfHeader.toLowerCase(java.util.Locale.ROOT);
            org.assertj.core.api.Assertions.assertThat(normalizedCookie.contains("path=/")).as("CSRF cookie path").isTrue();
            org.assertj.core.api.Assertions.assertThat(normalizedCookie.contains("samesite=lax")).as("CSRF cookie SameSite").isTrue();
            org.assertj.core.api.Assertions.assertThat(normalizedCookie.contains("httponly")).as("CSRF cookie must be readable by the API client").isFalse();
            org.assertj.core.api.Assertions.assertThat(normalizedCookie.contains("; secure")).as("local CSRF cookie secure mode").isFalse();
        }
    }

    @Test void viewerCanReadOperationalDataButCannotWriteOrAdminister() throws Exception {
        when(resources.list(0, 100)).thenReturn(List.of());
        when(alerts.list(null, 0, 100)).thenReturn(List.of());
        mvc.perform(get("/api/resources").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/alerts").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-events").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/resources").with(user("viewer").roles("VIEWER")).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(resources, never()).create(any());
        mvc.perform(patch("/api/alerts/" + UUID.randomUUID() + "/status").with(user("viewer").roles("VIEWER")).with(csrf())
                .contentType("application/json").content("{\"status\":\"RESOLVED\"}"))
                .andExpect(status().isForbidden());
    }

    @Test void devopsCanPerformOperationalWritesButCannotUseAdminOrAuditWriteRoutes() throws Exception {
        when(resources.list(0, 100)).thenReturn(List.of());
        when(auditEvents.list(100)).thenReturn(List.of());
        mvc.perform(get("/api/resources").with(user("ops").roles("DEVOPS"))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-events").with(user("ops").roles("DEVOPS"))).andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").with(user("ops").roles("DEVOPS"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/audit-events").with(user("ops").roles("DEVOPS")).with(csrf())
                .contentType("application/json").content("{\"eventType\":\"TEST\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(delete("/api/resources/" + UUID.randomUUID()).with(user("ops").roles("DEVOPS")).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test void adminCanReadUserManagementAndWriteAuditWithCsrf() throws Exception {
        when(users.findAll()).thenReturn(List.of());
        when(auditEvents.record(any())).thenReturn(new AuditEvent("TEST", "admin", null, null, Instant.now(), "SUCCESS", Map.of()));
        mvc.perform(get("/api/admin/users").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/api/audit-events").with(user("admin").roles("ADMIN"))
                .contentType("application/json").content("{\"eventType\":\"TEST\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/audit-events").with(user("admin").roles("ADMIN")).with(csrf().useInvalidToken())
                .contentType("application/json").content("{\"eventType\":\"TEST\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/audit-events").with(user("admin").roles("ADMIN")).with(csrf())
                .contentType("application/json").content("{\"eventType\":\"TEST\",\"outcome\":\"SUCCESS\"}"))
                .andExpect(status().isCreated());
    }

    @Test void successfulLoginPersistsSessionAndReturnsCurrentUser() throws Exception {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount("admin", "CloudShield Admin", "encoded-hash", UserRole.ADMIN);
        ReflectionTestUtils.setField(account, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(account));
        UserPrincipal principal = UserPrincipal.from(account);
        var authenticated = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        when(authenticationManager.authenticate(any())).thenReturn(authenticated);
        when(loginLimiter.allow(anyString())).thenReturn(true);
        String loginBody = "{\"username\":\"admin\",\"password\":\"" + UUID.randomUUID() + "\"}";
        MockHttpSession initialSession = new MockHttpSession();
        String priorSessionId = initialSession.getId();

        MvcResult result = mvc.perform(post("/api/auth/login").session(initialSession).with(csrf()).contentType("application/json").content(loginBody))
                .andExpect(status().isOk()).andExpect(content().json("{\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\"}"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();
        org.assertj.core.api.Assertions.assertThat(session.getId()).isNotEqualTo(priorSessionId);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(content().json("{\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\"}"));
        mvc.perform(post("/api/auth/logout").session(session).with(csrf())).andExpect(status().isNoContent());
        org.assertj.core.api.Assertions.assertThat(session.isInvalid()).isTrue();
        verify(securityAudit).record(eq("LOGIN_SUCCESS"), eq("admin"), eq("admin"), eq("SUCCESS"), eq(Map.of()));
    }

    @Test void mfaLoginRequiresChallengeBeforeCreatingAuthenticatedSession() throws Exception {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount("mfa-user", "MFA User", "encoded-hash", UserRole.VIEWER);
        ReflectionTestUtils.setField(account, "id", id);
        UserPrincipal principal = UserPrincipal.from(account);
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        when(loginLimiter.allow(anyString())).thenReturn(true);
        when(mfaService.isMfaEnabled("mfa-user")).thenReturn(true);
        when(mfaAttemptLimiter.allow(anyString())).thenReturn(true);
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"mfa-user\",\"password\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isAccepted()).andExpect(content().json("{\"mfaRequired\":true}"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();
        org.assertj.core.api.Assertions.assertThat(session.getAttribute(MfaController.CHALLENGE_USERNAME)).isEqualTo("mfa-user");
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized());

        when(mfaService.verifyLoginCode("mfa-user", "123456")).thenReturn(true);
        when(users.findByUsername("mfa-user")).thenReturn(Optional.of(account));
        when(users.findById(id)).thenReturn(Optional.of(account));
        mvc.perform(post("/api/auth/mfa/challenge").session(session).with(csrf()).contentType("application/json")
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isOk()).andExpect(content().json("{\"username\":\"mfa-user\",\"role\":\"VIEWER\"}"));
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(session.getAttribute(MfaController.CHALLENGE_USERNAME)).isNull();
    }

    @Test void mfaChallengeRequiresCsrfAndRejectsMissingChallengeSession() throws Exception {
        when(mfaAttemptLimiter.allow(anyString())).thenReturn(true);
        mvc.perform(post("/api/auth/mfa/challenge").contentType("application/json").content("{\"code\":\"123456\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/auth/mfa/challenge").with(csrf()).contentType("application/json").content("{\"code\":\"123456\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/mfa/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/mfa/enrollment").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test void invalidMfaCodeIsGenericAndAuditedWithoutRecordingTheCode() throws Exception {
        UserAccount account = new UserAccount("mfa-user", "MFA User", "encoded-hash", UserRole.VIEWER);
        UserPrincipal principal = UserPrincipal.from(account);
        when(authenticationManager.authenticate(any())).thenReturn(new UsernamePasswordAuthenticationToken(principal, null, principal.authorities()));
        when(loginLimiter.allow(anyString())).thenReturn(true);
        when(mfaService.isMfaEnabled("mfa-user")).thenReturn(true);
        when(mfaAttemptLimiter.allow(anyString())).thenReturn(true);
        when(mfaService.verifyLoginCode("mfa-user", "000000")).thenReturn(false);
        MvcResult login = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json")
                        .content("{\"username\":\"mfa-user\",\"password\":\"test-only-password\"}"))
                .andExpect(status().isAccepted()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        mvc.perform(post("/api/auth/mfa/challenge").session(session).with(csrf()).contentType("application/json")
                        .content("{\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("000000"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid verification code")));
        verify(securityAudit).record(eq("MFA_LOGIN_FAILURE"), eq("mfa-user"), eq("mfa-user"), eq("FAILURE"), any());
    }

    @Test void invalidLoginIsGenericAndRateLimitRejectsBeforeAuthentication() throws Exception {
        when(loginLimiter.allow(anyString())).thenReturn(true, false);
        doThrow(new BadCredentialsException("details that must not reach the response"))
                .when(authenticationManager).authenticate(any());
        String body = "{\"username\":\"admin\",\"password\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized()).andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("details that must not reach the response"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Invalid username or password")));
        mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isTooManyRequests());
        verify(authenticationManager, times(1)).authenticate(any());
        verify(securityAudit).record(eq("LOGIN_FAILURE"), eq("admin"), eq("admin"), eq("FAILURE"), any());
    }

    @Test void currentUserReportsDatabaseRefreshedIdentity() throws Exception {
        UUID id = UUID.randomUUID();
        UserAccount account = new UserAccount("admin", "CloudShield Admin", "encoded-hash", UserRole.ADMIN);
        ReflectionTestUtils.setField(account, "id", id);
        when(users.findById(id)).thenReturn(Optional.of(account));
        UserPrincipal principal = new UserPrincipal(id, "admin", "Old display name", "encoded-hash", true,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_VIEWER")));
        var auth = new UsernamePasswordAuthenticationToken(principal, null, principal.authorities());
        org.springframework.mock.web.MockHttpSession session = new org.springframework.mock.web.MockHttpSession();
        String priorSessionId = session.getId();
        mvc.perform(get("/api/auth/me").session(session).with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().json("{\"id\":\"" + id + "\",\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\",\"enabled\":true}"));
        org.assertj.core.api.Assertions.assertThat(session.getId()).isNotEqualTo(priorSessionId);
    }

    @Test void probeKeyOnlyAuthorizesHeartbeatPostAndCannotReadProtectedRoutes() throws Exception {
        mvc.perform(post("/api/probe/heartbeat").header("X-Probe-Key", PROBE_KEY)
                .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/probe/heartbeat").header("X-Probe-Key", UUID.randomUUID().toString()).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/probe/heartbeats").header("X-Probe-Key", PROBE_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/resources/" + UUID.randomUUID() + "/metrics").header("X-Probe-Key", PROBE_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/resources").header("X-Probe-Key", PROBE_KEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/alerts").header("X-Probe-Key", PROBE_KEY).with(csrf())
                .contentType("application/json").content("{\"alertType\":\"TEST\",\"severity\":\"HIGH\",\"description\":\"probe cannot create alerts\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void probeKeyCanSubmitOnlyTelemetryAndIsRequiredForMetricIngestion() throws Exception {
        when(telemetry.ingest(any())).thenReturn(new ProbeMetricResponse(1, 0));
        String body = "{\"probeIdentifier\":\"probe-a\",\"resourceIdentifier\":\"host-a\",\"observations\":[{\"observationId\":\"" + UUID.randomUUID() + "\",\"metricType\":\"cpu.utilization\",\"value\":12.5,\"unit\":\"%\",\"collectedAt\":\"2026-10-10T00:00:00Z\"}]}";
        mvc.perform(post("/api/probe/metrics").header("X-Probe-Key", PROBE_KEY).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(content().json("{\"accepted\":1,\"duplicates\":0}"));
        mvc.perform(post("/api/probe/metrics").header("X-Probe-Key", UUID.randomUUID().toString()).with(csrf()).contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/alerts").header("X-Probe-Key", PROBE_KEY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/admin/users").header("X-Probe-Key", PROBE_KEY).with(csrf()).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test void devopsCanChangeAlertStateOnlyWithCsrfAndViewerCannot() throws Exception {
        UUID id = UUID.randomUUID();
        when(alerts.updateStatus(eq(id), eq("ACKNOWLEDGED"), eq("ops")))
                .thenReturn(new com.cloudshield.backend.domain.AlertRecord("CAPACITY", "HIGH", "Capacity high", null, "ACKNOWLEDGED"));
        mvc.perform(patch("/api/alerts/" + id + "/status").with(user("ops").roles("DEVOPS"))
                .contentType("application/json").content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(patch("/api/alerts/" + id + "/status").with(user("ops").roles("DEVOPS")).with(csrf())
                .contentType("application/json").content("{\"status\":\"ACKNOWLEDGED\"}"))
                .andExpect(status().isOk());
        verify(alerts).updateStatus(eq(id), eq("ACKNOWLEDGED"), eq("ops"));
        mvc.perform(get("/api/alerts?page=10001").with(user("viewer").roles("VIEWER"))).andExpect(status().isBadRequest());
    }
}
