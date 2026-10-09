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
        MetricController.class, AuditEventController.class, AlertController.class, AuthController.class, AdminUserController.class})
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
    @MockitoBean AuditEventService auditEvents;
    @MockitoBean AlertService alerts;
    @MockitoBean LoginAttemptLimiter loginLimiter;
    @MockitoBean SecurityAuditService securityAudit;

    @Test void unauthenticatedRequestsAreDeniedAndHealthRemainsPublic() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk());
        mvc.perform(get("/api/probe/heartbeat")).andExpect(status().isOk());
        mvc.perform(get("/api/resources")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
    }

    @Test void viewerCanReadOperationalDataButCannotWriteOrAdminister() throws Exception {
        when(resources.list(0, 100)).thenReturn(List.of());
        when(alerts.list(null, 100)).thenReturn(List.of());
        mvc.perform(get("/api/resources").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/alerts").with(user("viewer").roles("VIEWER"))).andExpect(status().isOk());
        mvc.perform(get("/api/audit-events").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users").with(user("viewer").roles("VIEWER"))).andExpect(status().isForbidden());
        mvc.perform(post("/api/resources").with(user("viewer").roles("VIEWER")).with(csrf())
                .contentType("application/json").content("{}"))
                .andExpect(status().isForbidden());
        verify(resources, never()).create(any());
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

        MvcResult result = mvc.perform(post("/api/auth/login").with(csrf()).contentType("application/json").content(loginBody))
                .andExpect(status().isOk()).andExpect(content().json("{\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\"}"))
                .andReturn();
        MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
        org.assertj.core.api.Assertions.assertThat(session).isNotNull();
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
                .andExpect(content().json("{\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\"}"));
        verify(securityAudit).record(eq("LOGIN_SUCCESS"), eq("admin"), eq("admin"), eq("SUCCESS"), eq(Map.of()));
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
        mvc.perform(get("/api/auth/me").with(authentication(auth)))
                .andExpect(status().isOk()).andExpect(content().json("{\"id\":\"" + id + "\",\"username\":\"admin\",\"displayName\":\"CloudShield Admin\",\"role\":\"ADMIN\",\"enabled\":true}"));
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
        mvc.perform(get("/api/resources").header("X-Probe-Key", PROBE_KEY))
                .andExpect(status().isUnauthorized());
    }
}
