package com.cloudshield.backend.service;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import com.cloudshield.backend.repository.UserAccountRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class BootstrapAdminInitializer implements ApplicationRunner {
    private final UserAccountRepository users;
    private final PasswordEncoder encoder;
    private final String username;
    private final String password;
    public BootstrapAdminInitializer(UserAccountRepository users, PasswordEncoder encoder,
            @Value("${cloudshield.bootstrap-admin.username:}") String username,
            @Value("${cloudshield.bootstrap-admin.password:}") String password) {
        this.users = users; this.encoder = encoder; this.username = username; this.password = password;
    }
    @Override @Transactional public void run(ApplicationArguments args) {
        if (users.countByRoleAndEnabledTrue(UserRole.ADMIN) > 0) return;
        if (username.isBlank() || password.isBlank())
            throw new IllegalStateException("No enabled ADMIN account exists. Configure BOOTSTRAP_ADMIN_USERNAME and BOOTSTRAP_ADMIN_PASSWORD once to create the initial administrator.");
        if (username.trim().length() > 80 || password.length() < 14 || password.length() > 200)
            throw new IllegalStateException("Bootstrap admin username must be at most 80 characters and password must be 14-200 characters.");
        String normalized = username.trim().toLowerCase(java.util.Locale.ROOT);
        if (!normalized.matches("[a-z0-9._@-]+")) throw new IllegalStateException("Bootstrap admin username may contain letters, digits, dot, underscore, at sign, or hyphen only.");
        if (users.findByUsername(normalized).isPresent())
            throw new IllegalStateException("The configured bootstrap username exists but no enabled ADMIN account exists; review user state before starting.");
        users.save(new UserAccount(normalized, normalized, encoder.encode(password), UserRole.ADMIN));
    }
}
