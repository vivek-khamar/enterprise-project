-- ============================================================
-- V1 — Initial schema for enterprise-project (PostgreSQL)
-- Apply manually in QA / prod (ddl-auto: validate does not auto-apply).
-- Hibernate validates the schema at startup; every column here must
-- match the corresponding @Column definition in the JPA entities.
-- ============================================================

-- ── users ──────────────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS users (
    id          BIGSERIAL    PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    email       VARCHAR(254) NOT NULL,
    password    VARCHAR(255) NOT NULL,
    role        VARCHAR(20)  NOT NULL DEFAULT 'USER',
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    -- Audit timestamps (GDPR Art.5(2) — accountability)
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_username UNIQUE (username),
    CONSTRAINT uq_users_email    UNIQUE (email)
);

-- Keep updated_at current automatically (supplement to Hibernate @UpdateTimestamp)
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_updated_at
BEFORE UPDATE ON users
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

-- ── refresh_tokens ──────────────────────────────────────────────────────────
-- Tokens are personal data (linked to an identifiable user) and must be
-- deleted when the account is deleted (GDPR Art.17) or when they expire
-- (data minimisation, Art.5(1)(e)).  The application cascade-deletes these
-- in UserService.deleteUser() and TokenCleanupScheduler runs a daily purge.
CREATE TABLE IF NOT EXISTS refresh_tokens (
    id          BIGSERIAL    PRIMARY KEY,
    token       VARCHAR(255) NOT NULL,
    user_id     BIGINT       NOT NULL,
    expires_at  TIMESTAMPTZ  NOT NULL,
    revoked     BOOLEAN      NOT NULL DEFAULT FALSE,
    CONSTRAINT uq_refresh_tokens_token UNIQUE (token),
    CONSTRAINT fk_refresh_tokens_user
        FOREIGN KEY (user_id)
        REFERENCES users (id)
        ON DELETE CASCADE   -- DB-level safety net; application also deletes explicitly
);

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id  ON refresh_tokens (user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires  ON refresh_tokens (expires_at)
    WHERE revoked = FALSE;   -- partial index — only active tokens need fast expiry scans

-- ── file_metadata ───────────────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS file_metadata (
    id                BIGSERIAL    PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    s3_key            VARCHAR(255) NOT NULL,
    s3_bucket         VARCHAR(255) NOT NULL,
    content_type      VARCHAR(255) NOT NULL,
    file_size         BIGINT       NOT NULL,
    uploaded_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    uploaded_by       VARCHAR(50)  NOT NULL,
    CONSTRAINT uq_file_metadata_s3_key UNIQUE (s3_key)
);

CREATE INDEX IF NOT EXISTS idx_file_metadata_uploaded_by ON file_metadata (uploaded_by);
