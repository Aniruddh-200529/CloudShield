package com.cloudshield.backend.service;

import com.cloudshield.backend.domain.UserAccount;
import com.cloudshield.backend.repository.UserAccountRepository;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MfaService {
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final UserAccountRepository users;
    private final PasswordEncoder passwords;
    private final Clock clock;
    private final String encryptionKey;

    public MfaService(UserAccountRepository users, PasswordEncoder passwords, Clock clock,
            @Value("${cloudshield.mfa.encryption-key:}") String encryptionKey) {
        this.users = users;
        this.passwords = passwords;
        this.clock = clock;
        this.encryptionKey = encryptionKey;
    }

    public Enrollment createEnrollment(String username) {
        byte[] configuredKey = loadEncryptionKey();
        Arrays.fill(configuredKey, (byte) 0);
        UserAccount account = users.findByUsername(username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (account.isMfaEnabled()) throw new ConflictException("MFA is already enabled");
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        String secret = base32(bytes);
        Arrays.fill(bytes, (byte) 0);
        String uri = "otpauth://totp/" + URLEncoder.encode("CloudShield:" + account.getUsername(), StandardCharsets.UTF_8)
                + "?secret=" + secret + "&issuer=CloudShield&algorithm=SHA1&digits=6&period=30";
        return new Enrollment(secret, uri);
    }

    @Transactional
    public boolean confirmEnrollment(String username, String secret, String code) {
        UserAccount account = users.findByUsernameForUpdate(username.toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (!account.isEnabled() || account.isMfaEnabled()) return false;
        long step = matchingStep(secret, code, currentStep(), -1);
        if (step < 0) return false;
        account.enableMfa(encrypt(secret), step);
        return true;
    }

    @Transactional
    public boolean verifyLoginCode(String username, String code) {
        UserAccount account = users.findByUsernameForUpdate(username.toLowerCase(Locale.ROOT))
                .orElse(null);
        if (account == null || !account.isEnabled() || !account.isMfaEnabled()) return false;
        String secret = decrypt(account.getMfaSecretCiphertext());
        long priorStep = account.getMfaLastUsedStep() == null ? -1 : account.getMfaLastUsedStep();
        long matched = matchingStep(secret, code, currentStep(), priorStep);
        if (matched < 0) return false;
        account.recordMfaStep(matched);
        return true;
    }

    @Transactional
    public boolean disable(String username, String password, String code) {
        UserAccount account = users.findByUsernameForUpdate(username.toLowerCase(Locale.ROOT))
                .orElse(null);
        if (account == null || !account.isEnabled() || !account.isMfaEnabled()
                || !passwords.matches(password, account.getPasswordHash())) return false;
        String secret = decrypt(account.getMfaSecretCiphertext());
        long priorStep = account.getMfaLastUsedStep() == null ? -1 : account.getMfaLastUsedStep();
        long matched = matchingStep(secret, code, currentStep(), priorStep);
        if (matched < 0) return false;
        account.disableMfa();
        return true;
    }

    public boolean isMfaEnabled(String username) {
        return users.findByUsername(username.toLowerCase(Locale.ROOT)).map(UserAccount::isMfaEnabled).orElse(false);
    }

    private long currentStep() { return clock.instant().getEpochSecond() / 30; }

    private long matchingStep(String secret, String code, long current, long lastUsed) {
        if (code == null || !code.matches("[0-9]{6}")) return -1;
        for (long step = current - 1; step <= current + 1; step++) {
            if (step <= lastUsed) continue;
            byte[] expected = totp(secret, step).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, code.getBytes(StandardCharsets.US_ASCII))) return step;
        }
        return -1;
    }

    private String totp(String secret, long step) {
        try {
            byte[] key = base32Decode(secret);
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(step).array());
            int offset = hash[hash.length - 1] & 0x0f;
            int binary = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
            return String.format(Locale.ROOT, "%06d", binary % 1_000_000);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("TOTP verification is unavailable");
        }
    }

    private String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(loadEncryptionKey(), "AES"), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
        } catch (GeneralSecurityException ex) {
            throw new MfaConfigurationException();
        }
    }

    private String decrypt(String ciphertext) {
        try {
            byte[] packed = Base64.getDecoder().decode(ciphertext);
            if (packed.length < 29) throw new GeneralSecurityException();
            byte[] iv = Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(loadEncryptionKey(), "AES"), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException ex) {
            throw new MfaConfigurationException();
        }
    }

    private byte[] loadEncryptionKey() {
        try {
            byte[] decoded = Base64.getDecoder().decode(encryptionKey);
            if (decoded.length != 32) throw new IllegalArgumentException();
            return decoded;
        } catch (IllegalArgumentException ex) {
            throw new MfaConfigurationException();
        }
    }

    private static String base32(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte value : bytes) {
            buffer = (buffer << 8) | (value & 0xff);
            bits += 8;
            while (bits >= 5) { result.append(ALPHABET.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
            buffer &= (1 << bits) - 1;
        }
        if (bits > 0) result.append(ALPHABET.charAt((buffer << (5 - bits)) & 31));
        return result.toString();
    }

    private static byte[] base32Decode(String value) {
        byte[] output = new byte[value.length() * 5 / 8];
        int buffer = 0, bits = 0, index = 0;
        for (char character : value.toUpperCase(Locale.ROOT).toCharArray()) {
            int digit = ALPHABET.indexOf(character);
            if (digit < 0) throw new IllegalArgumentException("Invalid TOTP secret");
            buffer = (buffer << 5) | digit;
            bits += 5;
            if (bits >= 8) { output[index++] = (byte) (buffer >> (bits - 8)); bits -= 8; buffer &= (1 << bits) - 1; }
        }
        return output;
    }

    public record Enrollment(String secret, String provisioningUri) {}
}
