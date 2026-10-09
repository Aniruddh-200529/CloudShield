package com.cloudshield.backend.security;

import com.cloudshield.backend.api.ApiError;
import tools.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
    @Bean PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }
    @Bean SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }
    @Bean FilterRegistrationBean<CurrentUserFilter> currentUserFilterRegistration(CurrentUserFilter filter) {
        FilterRegistrationBean<CurrentUserFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }
    @Bean DaoAuthenticationProvider daoAuthenticationProvider(DatabaseUserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return provider;
    }
    @Bean AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception { return configuration.getAuthenticationManager(); }

    @Bean SecurityFilterChain securityFilterChain(HttpSecurity http, ObjectMapper mapper, DaoAuthenticationProvider provider,
            CurrentUserFilter currentUserFilter,
            @Value("${cloudshield.probe.api-key:}") String probeKey) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookiePath("/");
        RequestMatcher heartbeatPost = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/probe/heartbeat");
        RequestMatcher metricsPost = PathPatternRequestMatcher.pathPattern(HttpMethod.POST, "/api/probe/metrics");
        http
            .csrf(csrf -> csrf.csrfTokenRepository(csrfRepository)
                    .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler())
                    .ignoringRequestMatchers(request -> (heartbeatPost.matches(request) || metricsPost.matches(request)) && validProbeKey(request, probeKey)))
            .cors(cors -> {})
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                    .sessionFixation(fixation -> fixation.changeSessionId()))
            .securityContext(context -> context.securityContextRepository(new HttpSessionSecurityContextRepository()))
            .authenticationProvider(provider)
            .authorizeHttpRequests(auth -> auth
                    .requestMatchers(HttpMethod.GET, "/api/health", "/api/probe/heartbeat", "/api/auth/csrf").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                    .requestMatchers(HttpMethod.POST, "/api/probe/heartbeat", "/api/probe/metrics").access((authentication, context) ->
                            new org.springframework.security.authorization.AuthorizationDecision(validProbeKey(context.getRequest(), probeKey)))
                    .requestMatchers("/api/auth/logout", "/api/auth/me").authenticated()
                    .requestMatchers("/api/admin/**").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.GET, "/api/resources/**", "/api/probe/heartbeats", "/api/alerts/**").hasAnyRole("ADMIN", "DEVOPS", "VIEWER")
                    .requestMatchers(HttpMethod.GET, "/api/audit-events").hasAnyRole("ADMIN", "DEVOPS")
                    .requestMatchers(HttpMethod.POST, "/api/audit-events").hasRole("ADMIN")
                    .requestMatchers(HttpMethod.POST, "/api/resources/**", "/api/resources").hasAnyRole("ADMIN", "DEVOPS")
                    .requestMatchers(HttpMethod.PUT, "/api/resources/**").hasAnyRole("ADMIN", "DEVOPS")
                    .requestMatchers(HttpMethod.PATCH, "/api/alerts/**").hasAnyRole("ADMIN", "DEVOPS")
                    .requestMatchers(HttpMethod.POST, "/api/alerts").hasAnyRole("ADMIN", "DEVOPS")
                    .requestMatchers(HttpMethod.DELETE, "/api/resources/**").hasRole("ADMIN")
                    .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                    .authenticationEntryPoint((request, response, exception) -> writeError(mapper, request, response, HttpStatus.UNAUTHORIZED, "Authentication is required"))
                    .accessDeniedHandler((request, response, exception) -> writeError(mapper, request, response, HttpStatus.FORBIDDEN, "Access is denied")))
            .addFilterAfter(currentUserFilter, SecurityContextHolderFilter.class)
            .formLogin(form -> form.disable()).httpBasic(basic -> basic.disable()).logout(logout -> logout.disable());
        return http.build();
    }
    private static boolean validProbeKey(HttpServletRequest request, String configured) {
        String supplied = request.getHeader("X-Probe-Key");
        return configured != null && !configured.isBlank() && supplied != null &&
                java.security.MessageDigest.isEqual(configured.getBytes(java.nio.charset.StandardCharsets.UTF_8), supplied.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static void writeError(ObjectMapper mapper, HttpServletRequest request, jakarta.servlet.http.HttpServletResponse response,
            HttpStatus status, String message) throws java.io.IOException {
        response.setStatus(status.value()); response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI(), Map.of()));
    }
}
