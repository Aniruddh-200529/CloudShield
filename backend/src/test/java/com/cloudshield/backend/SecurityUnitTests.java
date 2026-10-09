package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import com.cloudshield.backend.repository.UserAccountRepository;
import com.cloudshield.backend.service.BootstrapAdminInitializer;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import com.cloudshield.backend.service.LoginAttemptLimiter;

class SecurityUnitTests {
    private static final String TEST_PASSWORD = UUID.randomUUID().toString();
    @Test void limitsRepeatedLoginFailuresPerClient() {
        LoginAttemptLimiter limiter = new LoginAttemptLimiter();
        for (int attempt = 0; attempt < 10; attempt++) {
            assertThat(limiter.allow("127.0.0.1")).isTrue();
            limiter.failed("127.0.0.1");
        }
        assertThat(limiter.allow("127.0.0.1")).isFalse();
        assertThat(limiter.allow("127.0.0.2")).isTrue();
    }

    @Test void bootstrapIsInsertOnlyWhenAnAdministratorAlreadyExists() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(1L);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(users, new BCryptPasswordEncoder(4), "first-admin", TEST_PASSWORD);
        initializer.run(new DefaultApplicationArguments(new String[0]));
        verify(users, never()).save(any(UserAccount.class));
    }

    @Test void bootstrapRequiresAConfiguredAdminWhenNoEnabledAdministratorExists() {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(0L);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(users, new BCryptPasswordEncoder(4), "", "");
        assertThatThrownBy(() -> initializer.run(new DefaultApplicationArguments(new String[0])))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("BOOTSTRAP_ADMIN_USERNAME");
        verify(users, never()).save(any(UserAccount.class));
    }

    @Test void bootstrapStoresOnlyEncodedPasswordAndNormalizedUsername() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        when(users.countByRoleAndEnabledTrue(UserRole.ADMIN)).thenReturn(0L);
        when(users.findByUsername("first-admin")).thenReturn(Optional.empty());
        when(users.save(any(UserAccount.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(4);
        BootstrapAdminInitializer initializer = new BootstrapAdminInitializer(users, encoder, " First-Admin ", TEST_PASSWORD);
        initializer.run(new DefaultApplicationArguments(new String[0]));
        var captor = org.mockito.ArgumentCaptor.forClass(UserAccount.class);
        verify(users).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("first-admin");
        assertThat(captor.getValue().getRole()).isEqualTo(UserRole.ADMIN);
        assertThat(captor.getValue().getPasswordHash()).isNotEqualTo(TEST_PASSWORD);
        assertThat(encoder.matches(TEST_PASSWORD, captor.getValue().getPasswordHash())).isTrue();
    }
}
