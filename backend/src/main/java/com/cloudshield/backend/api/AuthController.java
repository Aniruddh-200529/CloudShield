package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.security.UserPrincipal;
import com.cloudshield.backend.service.LoginAttemptLimiter;
import com.cloudshield.backend.service.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController @RequestMapping("/api/auth")
public class AuthController {
    private final AuthenticationManager authenticationManager;
    private final SecurityContextRepository contexts;
    private final LoginAttemptLimiter limiter;
    private final SecurityAuditService audit;
    public AuthController(AuthenticationManager authenticationManager, SecurityContextRepository contexts,
            LoginAttemptLimiter limiter, SecurityAuditService audit) {
        this.authenticationManager = authenticationManager; this.contexts = contexts; this.limiter = limiter; this.audit = audit;
    }
    @GetMapping("/csrf") public Map<String, String> csrf(CsrfToken token) { return Map.of("headerName", token.getHeaderName(), "token", token.getToken()); }
    @PostMapping("/login") public ResponseEntity<?> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
        String username = body.username().trim().toLowerCase(java.util.Locale.ROOT);
        String client = request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
        if (!limiter.allow(client)) return ResponseEntity.status(429).body(new ApiError(Instant.now(), 429, "Too Many Requests", "Too many login attempts", request.getRequestURI(), Map.of()));
        try {
            Authentication auth = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(username, body.password()));
            if (!(auth.getPrincipal() instanceof UserPrincipal principal)) throw new BadCredentialsException("Invalid username or password");
            audit.record("LOGIN_SUCCESS", username, username, "SUCCESS", Map.of());
            request.getSession(true);
            request.changeSessionId();
            SecurityContext context = SecurityContextHolder.createEmptyContext(); context.setAuthentication(auth);
            SecurityContextHolder.setContext(context); contexts.saveContext(context, request, response);
            limiter.succeeded(client);
            return ResponseEntity.ok(UserResponse.fromPrincipal(principal));
        } catch (AuthenticationException ex) {
            limiter.failed(client);
            audit.record("LOGIN_FAILURE", username, username, "FAILURE", Map.of("source", client));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ApiError(Instant.now(), 401, "Unauthorized", "Invalid username or password", request.getRequestURI(), Map.of()));
        }
    }
    @GetMapping("/me") public UserResponse me(Authentication authentication) {
        return UserResponse.fromPrincipal((UserPrincipal) authentication.getPrincipal());
    }
    @PostMapping("/logout") public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response, Authentication auth) {
        String username = auth.getName(); audit.record("LOGOUT", username, username, "SUCCESS", Map.of());
        SecurityContextHolder.clearContext();
        if (request.getSession(false) != null) request.getSession(false).invalidate();
        var cookie = new jakarta.servlet.http.Cookie("XSRF-TOKEN", ""); cookie.setPath("/"); cookie.setMaxAge(0); response.addCookie(cookie);
        return ResponseEntity.noContent().build();
    }
    public record LoginRequest(@NotBlank @Size(max = 80) @jakarta.validation.constraints.Pattern(regexp = "[A-Za-z0-9._@-]+") String username, @NotBlank @Size(max = 200) String password) {}
    public record UserResponse(java.util.UUID id, String username, String displayName, String role, boolean enabled) {
        static UserResponse fromPrincipal(UserPrincipal principal) { return new UserResponse(principal.id(), principal.username(), principal.displayName(), principal.getAuthorities().stream().findFirst().orElseThrow().getAuthority().replace("ROLE_", ""), principal.isEnabled()); }
        static UserResponse from(UserAccount user) { return new UserResponse(user.getId(), user.getUsername(), user.getDisplayName(), user.getRole().name(), user.isEnabled()); }
    }
}
