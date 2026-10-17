-- Additive verification fields. Existing accounts stay verified and keep access.
ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS email_verification_code_hash VARCHAR(60),
    ADD COLUMN IF NOT EXISTS email_verification_expires_at TIMESTAMPTZ;
