package com.cloudshield.backend.api;

import com.cloudshield.backend.security.UserPrincipal;
import com.cloudshield.backend.service.LoginAttemptLimiter;
import com.cloudshield.backend.service.MfaAttemptLimiter;
import com.cloudshield.backend.service.MfaService;
import com.cloudshield.backend.service.SecurityAuditService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth/mfa")
public class MfaController {
    public static final String CHALLENGE_USERNAME = MfaController.class.getName() + ".challengeUsername";
    public static final String CHALLENGE_STARTED_AT = MfaController.class.getName() + ".challengeStartedAt";
    public static final String ENROLLMENT_SECRET = MfaController.class.getName() + ".enrollmentSecret";
    public static final String ENROLLMENT_USERNAME = MfaController.class.getName() + ".enrollmentUsername";
    private final MfaService mfa;
    private final MfaAttemptLimiter mfaLimiter;
    private final LoginAttemptLimiter loginLimiter;
    private final SecurityAuditService audit;
    private final UserDetailsService users;
    private final SecurityContextRepository contexts;
    private final Clock clock;

    public MfaController(MfaService mfa, MfaAttemptLimiter mfaLimiter, LoginAttemptLimiter loginLimiter,
            SecurityAuditService audit, UserDetailsService users, SecurityContextRepository contexts, Clock clock) {
        this.mfa = mfa; this.mfaLimiter = mfaLimiter; this.loginLimiter = loginLimiter;
        this.audit = audit; this.users = users; this.contexts = contexts; this.clock = clock;
    }

    @GetMapping("/status")
    public MfaStatus status(Authentication authentication) {
        return new MfaStatus(mfa.isMfaEnabled(authentication.getName()));
    }

    @PostMapping("/enrollment")
    public EnrollmentResponse beginEnrollment(Authentication authentication, HttpServletRequest request) {
        MfaService.Enrollment enrollment = mfa.createEnrollment(authentication.getName());
        request.getSession(true).setAttribute(ENROLLMENT_SECRET, enrollment.secret());
        request.getSession(false).setAttribute(ENROLLMENT_USERNAME, authentication.getName());
        return new EnrollmentResponse(enrollment.provisioningUri());
    }

    @PostMapping("/confirm")
    @Transactional
    public ResponseEntity<?> confirm(@Valid @RequestBody CodeRequest body, Authentication authentication,
            HttpServletRequest request) {
        String key = rateKey(request);
        if (!mfaLimiter.allow(key)) return tooMany(request);
        Object pending = request.getSession(false) == null ? null : request.getSession(false).getAttribute(ENROLLMENT_SECRET);
        Object owner = request.getSession(false) == null ? null : request.getSession(false).getAttribute(ENROLLMENT_USERNAME);
        if (!(pending instanceof String secret) || !authentication.getName().equals(owner)) {
            if (request.getSession(false) != null) {
                request.getSession(false).removeAttribute(ENROLLMENT_SECRET);
                request.getSession(false).removeAttribute(ENROLLMENT_USERNAME);
            }
            mfaLimiter.failed(key);
            audit.record("MFA_ENROLLMENT_FAILURE", authentication.getName(), authentication.getName(), "FAILURE", Map.of());
            return ResponseEntity.badRequest().body(error(request, 400, "Verification failed"));
        }
        boolean valid = mfa.confirmEnrollment(authentication.getName(), secret, body.code());
        if (!valid) {
            mfaLimiter.failed(key);
            audit.record("MFA_ENROLLMENT_FAILURE", authentication.getName(), authentication.getName(), "FAILURE", Map.of());
            return ResponseEntity.badRequest().body(error(request, 400, "Verification failed"));
        }
        request.getSession(false).removeAttribute(ENROLLMENT_SECRET);
        request.getSession(false).removeAttribute(ENROLLMENT_USERNAME);
        mfaLimiter.succeeded(key);
        audit.record("MFA_ENROLLMENT", authentication.getName(), authentication.getName(), "SUCCESS", Map.of());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/challenge")
    public ResponseEntity<?> challenge(@Valid @RequestBody CodeRequest body, HttpServletRequest request,
            HttpServletResponse response) {
        String key = rateKey(request);
        if (!mfaLimiter.allow(key)) return tooMany(request);
        Object username = request.getSession(false) == null ? null : request.getSession(false).getAttribute(CHALLENGE_USERNAME);
        if (!(username instanceof String name)) return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(error(request, 401, "Invalid verification code"));
        Object started = request.getSession(false).getAttribute(CHALLENGE_STARTED_AT);
        if (!(started instanceof Instant startedAt) || Duration.between(startedAt, clock.instant()).compareTo(Duration.ofMinutes(5)) > 0) {
            request.getSession(false).removeAttribute(CHALLENGE_USERNAME);
            request.getSession(false).removeAttribute(CHALLENGE_STARTED_AT);
            audit.record("MFA_LOGIN_EXPIRED", name, name, "FAILURE", Map.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, 401, "Invalid or expired verification challenge"));
        }
        if (!mfa.verifyLoginCode(name, body.code())) {
            mfaLimiter.failed(key);
            audit.record("MFA_LOGIN_FAILURE", name, name, "FAILURE", Map.of("source", key));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, 401, "Invalid verification code"));
        }
        UserPrincipal principal;
        try {
            principal = (UserPrincipal) users.loadUserByUsername(name);
        } catch (org.springframework.security.core.AuthenticationException ex) {
            audit.record("MFA_LOGIN_FAILURE", name, name, "FAILURE", Map.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, 401, "Invalid verification code"));
        }
        if (!principal.isEnabled()) {
            audit.record("MFA_LOGIN_FAILURE", name, name, "FAILURE", Map.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, 401, "Invalid verification code"));
        }
        Authentication auth = UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities());
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context);
        contexts.saveContext(context, request, response);
        request.getSession(false).removeAttribute(CHALLENGE_USERNAME);
        request.getSession(false).removeAttribute(CHALLENGE_STARTED_AT);
        mfaLimiter.succeeded(key);
        loginLimiter.succeeded(key);
        audit.record("LOGIN_SUCCESS", name, name, "SUCCESS", Map.of("mfa", true));
        return ResponseEntity.ok(AuthController.UserResponse.fromPrincipal(principal));
    }

    @PostMapping("/disable")
    @Transactional
    public ResponseEntity<?> disable(@Valid @RequestBody DisableRequest body, Authentication authentication,
            HttpServletRequest request) {
        String key = rateKey(request);
        if (!mfaLimiter.allow(key)) return tooMany(request);
        if (!mfa.disable(authentication.getName(), body.password(), body.code())) {
            mfaLimiter.failed(key);
            audit.record("MFA_DISABLE_FAILURE", authentication.getName(), authentication.getName(), "FAILURE", Map.of());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(request, 401, "Reauthentication failed"));
        }
        mfaLimiter.succeeded(key);
        audit.record("MFA_DISABLED", authentication.getName(), authentication.getName(), "SUCCESS", Map.of());
        return ResponseEntity.noContent().build();
    }

    private ResponseEntity<ApiError> tooMany(HttpServletRequest request) {
        return ResponseEntity.status(429).body(error(request, 429, "Too many MFA verification attempts"));
    }
    private ApiError error(HttpServletRequest request, int status, String message) {
        String reason = status == 429 ? "Too Many Requests" : status == 401 ? "Unauthorized" : "Bad Request";
        return new ApiError(Instant.now(), status, reason, message, request.getRequestURI(), Map.of());
    }
    private String rateKey(HttpServletRequest request) {
        return request.getRemoteAddr() == null ? "unknown" : request.getRemoteAddr();
    }

    public record MfaStatus(boolean enabled) {}
    public record EnrollmentResponse(String provisioningUri) {}
    public record CodeRequest(@NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
    public record DisableRequest(@NotBlank @Size(max = 200) String password,
            @NotBlank @Pattern(regexp = "[0-9]{6}") String code) {}
}
