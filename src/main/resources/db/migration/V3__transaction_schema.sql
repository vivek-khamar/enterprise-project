CREATE TABLE IF NOT EXISTS transactions (
    id                  BIGSERIAL        PRIMARY KEY,
    user_id             BIGINT           NOT NULL,
    merchant            VARCHAR(255)     NOT NULL,
    description         TEXT             NOT NULL,
    amount              NUMERIC(19, 4)   NOT NULL,
    currency            CHAR(3)          NOT NULL DEFAULT 'USD',
    transaction_date    DATE             NOT NULL,
    category            VARCHAR(30),
    category_confidence DOUBLE PRECISION,
    category_reasoning  TEXT,
    fraud_risk          VARCHAR(10),
    fraud_flags         TEXT,
    created_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ      NOT NULL DEFAULT now(),
    CONSTRAINT fk_tx_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_tx_user_id    ON transactions(user_id);
CREATE INDEX IF NOT EXISTS idx_tx_fraud_risk ON transactions(fraud_risk)
    WHERE fraud_risk IN ('MEDIUM', 'HIGH');

CREATE TRIGGER trg_transactions_updated_at
    BEFORE UPDATE ON transactions
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
