package com.cloudshield.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.domain.UserRole;
import com.cloudshield.backend.repository.UserAccountRepository;
import com.cloudshield.backend.service.MfaConfigurationException;
import com.cloudshield.backend.service.MfaAttemptLimiter;
import com.cloudshield.backend.service.MfaService;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class MfaServiceTests {
    private static final String SECRET = "GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ";
    private static final String KEY = Base64.getEncoder().encodeToString(new byte[32]);

    @Test void enrollmentEncryptsSecretAndRejectsReplayedTotpSteps() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount account = new UserAccount("mfa-user", "MFA User", "hash", UserRole.VIEWER);
        when(users.findByUsernameForUpdate("mfa-user")).thenReturn(Optional.of(account));
        MfaService service = service(users, passwords, 59, KEY);

        assertThat(service.confirmEnrollment("mfa-user", SECRET, "287082")).isTrue();
        assertThat(account.isMfaEnabled()).isTrue();
        assertThat(account.getMfaSecretCiphertext()).isNotEqualTo(SECRET).isNotBlank();
        assertThat(service.verifyLoginCode("mfa-user", "287082")).isFalse();

        MfaService nextStep = service(users, passwords, 90, KEY);
        String nextCode = codeForStep(SECRET, 3);
        assertThat(nextStep.verifyLoginCode("mfa-user", nextCode)).isTrue();
        assertThat(nextStep.verifyLoginCode("mfa-user", nextCode)).isFalse();
    }

    @Test void missingOrInvalidEncryptionKeyFailsClosedBeforeEnrollment() {
        MfaService service = service(mock(UserAccountRepository.class), mock(PasswordEncoder.class), 0, "");
        assertThatThrownBy(() -> service.createEnrollment("mfa-user")).isInstanceOf(MfaConfigurationException.class);
    }

    @Test void generatedProvisioningUriCanCompleteEnrollment() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        UserAccount account = new UserAccount("mfa-user", "MFA User", "hash", UserRole.DEVOPS);
        when(users.findByUsername("mfa-user")).thenReturn(Optional.of(account));
        when(users.findByUsernameForUpdate("mfa-user")).thenReturn(Optional.of(account));
        MfaService service = service(users, mock(PasswordEncoder.class), 59, KEY);
        MfaService.Enrollment enrollment = service.createEnrollment("mfa-user");
        assertThat(enrollment.provisioningUri()).startsWith("otpauth://totp/").contains("issuer=CloudShield");
        String secret = enrollment.provisioningUri().split("secret=")[1].split("&")[0];
        assertThat(secret).matches("[A-Z2-7]{32}");
        assertThat(service.confirmEnrollment("mfa-user", secret, codeForStep(secret, 1))).isTrue();
    }

    @Test void mfaVerificationLimiterRejectsAfterConfiguredFailuresAndCanReset() {
        MfaAttemptLimiter limiter = new MfaAttemptLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        for (int attempt = 0; attempt < 5; attempt++) {
            assertThat(limiter.allow("test-source")).isTrue();
            limiter.failed("test-source");
        }
        assertThat(limiter.allow("test-source")).isFalse();
        limiter.succeeded("test-source");
        assertThat(limiter.allow("test-source")).isTrue();
    }

    @Test void enrollmentAndDisableRequireValidCodesAndPassword() throws Exception {
        UserAccountRepository users = mock(UserAccountRepository.class);
        PasswordEncoder passwords = mock(PasswordEncoder.class);
        UserAccount account = new UserAccount("mfa-user", "MFA User", "hash", UserRole.ADMIN);
        when(users.findByUsernameForUpdate("mfa-user")).thenReturn(Optional.of(account));
        when(passwords.matches("correct-pass", "hash")).thenReturn(true);
        MfaService service = service(users, passwords, 59, KEY);
        assertThat(service.confirmEnrollment("mfa-user", SECRET, "000000")).isFalse();
        assertThat(account.isMfaEnabled()).isFalse();
        assertThat(service.confirmEnrollment("mfa-user", SECRET, "287082")).isTrue();
        MfaService atTwoMinutes = service(users, passwords, 120, KEY);
        assertThat(atTwoMinutes.disable("mfa-user", "correct-pass", codeForStep(SECRET, 4))).isTrue();
        assertThat(account.isMfaEnabled()).isFalse();
        assertThat(account.getMfaSecretCiphertext()).isNull();
    }

    private static MfaService service(UserAccountRepository users, PasswordEncoder passwords, long seconds, String key) {
        return new MfaService(users, passwords, Clock.fixed(Instant.ofEpochSecond(seconds), ZoneOffset.UTC), key);
    }

    private static String codeForStep(String base32, long step) throws Exception {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        byte[] key = new byte[base32.length() * 5 / 8];
        int buffer = 0, bits = 0, index = 0;
        for (char character : base32.toCharArray()) {
            buffer = (buffer << 5) | alphabet.indexOf(character); bits += 5;
            if (bits >= 8) { key[index++] = (byte) (buffer >> (bits - 8)); bits -= 8; buffer &= (1 << bits) - 1; }
        }
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(key, "HmacSHA1"));
        byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
        int offset = hash[hash.length - 1] & 15;
        int binary = ((hash[offset] & 127) << 24) | ((hash[offset + 1] & 255) << 16)
                | ((hash[offset + 2] & 255) << 8) | (hash[offset + 3] & 255);
        return String.format(java.util.Locale.ROOT, "%06d", binary % 1_000_000);
    }
}
