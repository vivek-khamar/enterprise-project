-- ============================================================
-- V2 — KYC verification schema
-- ============================================================

CREATE TABLE IF NOT EXISTS kyc_verifications (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           BIGINT       NOT NULL,
    file_metadata_id  BIGINT,
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    document_type     VARCHAR(100),
    extracted_data    TEXT,
    inconsistencies   TEXT,
    confidence_score  DOUBLE PRECISION,
    review_notes      TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_kyc_user FOREIGN KEY (user_id)
        REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_kyc_file FOREIGN KEY (file_metadata_id)
        REFERENCES file_metadata (id) ON DELETE SET NULL
);

CREATE INDEX IF NOT EXISTS idx_kyc_user_id ON kyc_verifications (user_id);
CREATE INDEX IF NOT EXISTS idx_kyc_status  ON kyc_verifications (status);

CREATE TRIGGER trg_kyc_updated_at
BEFORE UPDATE ON kyc_verifications
FOR EACH ROW EXECUTE FUNCTION set_updated_at();
