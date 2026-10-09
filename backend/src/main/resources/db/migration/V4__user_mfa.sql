ALTER TABLE user_accounts
    ADD COLUMN mfa_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN mfa_secret_ciphertext VARCHAR(512),
    ADD COLUMN mfa_last_used_step BIGINT,
    ADD CONSTRAINT ck_user_accounts_mfa_state CHECK (
        (mfa_enabled = FALSE AND mfa_secret_ciphertext IS NULL AND mfa_last_used_step IS NULL)
        OR (mfa_enabled = TRUE AND mfa_secret_ciphertext IS NOT NULL AND mfa_last_used_step IS NOT NULL)
    );
