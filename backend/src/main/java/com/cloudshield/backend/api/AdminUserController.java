package com.cloudshield.backend.api;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import com.cloudshield.backend.repository.UserAccountRepository;
import com.cloudshield.backend.service.ConflictException;
import com.cloudshield.backend.service.NotFoundException;
import com.cloudshield.backend.service.SecurityAuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.Authentication;

@RestController @RequestMapping("/api/admin/users") @Validated
public class AdminUserController {
    private final UserAccountRepository users; private final PasswordEncoder encoder; private final SecurityAuditService audit;
    public AdminUserController(UserAccountRepository users, PasswordEncoder encoder, SecurityAuditService audit) { this.users = users; this.encoder = encoder; this.audit = audit; }
    @GetMapping public List<AuthController.UserResponse> list() { return users.findAll().stream().map(AuthController.UserResponse::from).toList(); }
    @PostMapping @ResponseStatus(HttpStatus.CREATED) @Transactional public AuthController.UserResponse create(@Valid @RequestBody CreateUserRequest request, Authentication actor) {
        String username = request.username().trim().toLowerCase(Locale.ROOT);
        if (users.findByUsername(username).isPresent()) throw new ConflictException("Username is already in use");
        UserAccount created = users.save(new UserAccount(username, request.displayName().trim(), encoder.encode(request.password()), request.role()));
        audit.record("USER_CREATED", actor.getName(), username, "SUCCESS", java.util.Map.of("role", request.role().name()));
        return AuthController.UserResponse.from(created);
    }
    @PatchMapping("/{id}") @Transactional public AuthController.UserResponse updateDisplayName(@PathVariable UUID id, @Valid @RequestBody DisplayNameRequest request, Authentication actor) {
        UserAccount user = users.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("User not found"));
        user.changeDisplayName(request.displayName().trim()); audit.record("USER_UPDATED", actor.getName(), user.getUsername(), "SUCCESS", java.util.Map.of());
        return AuthController.UserResponse.from(user);
    }
    @PatchMapping("/{id}/role") @Transactional public AuthController.UserResponse updateRole(@PathVariable UUID id, @Valid @RequestBody RoleRequest request, Authentication actor) {
        var enabledAdmins = users.lockEnabledAdministrators(UserRole.ADMIN);
        UserAccount user = users.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getUsername().equals(actor.getName()) && request.role() != user.getRole()) throw new ConflictException("Administrators cannot change their own role");
        if (user.isEnabled() && user.getRole() == UserRole.ADMIN && request.role() != UserRole.ADMIN && enabledAdmins.size() <= 1) throw new ConflictException("Cannot remove the last enabled administrator");
        user.changeRole(request.role()); audit.record("USER_ROLE_CHANGED", actor.getName(), user.getUsername(), "SUCCESS", java.util.Map.of("role", request.role().name()));
        return AuthController.UserResponse.from(user);
    }
    @PatchMapping("/{id}/enabled") @Transactional public AuthController.UserResponse updateEnabled(@PathVariable UUID id, @Valid @RequestBody EnabledRequest request, Authentication actor) {
        var enabledAdmins = users.lockEnabledAdministrators(UserRole.ADMIN);
        UserAccount user = users.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("User not found"));
        if (user.getUsername().equals(actor.getName()) && !request.enabled()) throw new ConflictException("Administrators cannot disable their own account");
        if (!request.enabled() && user.isEnabled() && user.getRole() == UserRole.ADMIN && enabledAdmins.size() <= 1) throw new ConflictException("Cannot disable the last enabled administrator");
        user.setEnabled(request.enabled()); audit.record(request.enabled() ? "USER_ENABLED" : "USER_DISABLED", actor.getName(), user.getUsername(), "SUCCESS", java.util.Map.of());
        return AuthController.UserResponse.from(user);
    }
    public record CreateUserRequest(@NotBlank @Size(max = 80) @Pattern(regexp = "[A-Za-z0-9._@-]+") String username,
            @NotBlank @Size(min = 2, max = 120) String displayName, @NotBlank @Size(min = 14, max = 200) String password, @NotNull UserRole role) {}
    public record DisplayNameRequest(@NotBlank @Size(max = 120) String displayName) {}
    public record RoleRequest(@NotNull UserRole role) {}
    public record EnabledRequest(@NotNull Boolean enabled) {}
}
