# Data Protection & Compliance

## Applicable regulations

This document covers controls for **GDPR** (EU 2016/679) and aligns with
general data-protection best practice.  Adjust the scope if other regulations
apply (CCPA, HIPAA, PCI-DSS, etc.).

---

## Related documents

| Document | Purpose |
|----------|---------|
| [SECURITY.md](../SECURITY.md) | Secret inventory and rotation runbooks |
| [docs/secrets-management.md](secrets-management.md) | Detailed secrets strategy and CI/CD security |
| **[docs/breach-notification.md](breach-notification.md)** | **Step-by-step breach detection, containment, and GDPR Art.33/34 notification** |
| [docs/performance-sla.md](performance-sla.md) | SLA and load-test results |

---

## 1. PII inventory

| Field | Entity / table | Classification | Justification |
|-------|---------------|----------------|---------------|
| `username` | `users` | Pseudonymous identifier | Chosen by user; may be a real name |
| `email` | `users` | Direct identifier | Identifies a natural person |
| `password` (BCrypt hash) | `users` | Sensitive credential | Cannot be reversed; still personal data |
| `role` | `users` | Operational data | Not PII but linked to a person |
| `enabled` | `users` | Operational data | Not PII |
| `created_at` | `users` | Audit metadata | Timing of account creation |
| `updated_at` | `users` | Audit metadata | Last modification time |
| `token` | `refresh_tokens` | Session credential | Linked to a user via FK |
| `user_id` (FK) | `refresh_tokens` | Pseudonymous identifier | Links back to a real person |
| `originalFilename` | `file_metadata` | Potentially PII | File names can contain personal data |
| `s3Key` | `file_metadata` | Operational data | Storage reference |
| Access token (JWT) | In-memory / transport | Session credential | Encodes `userId` + `username` |

**Not stored:**
- Email addresses are **not** included in Kafka events (`UserEventPayload`)
  — data-minimisation principle (GDPR Art.5(1)(c)).

---

## 2. Lawful basis and purpose

| Processing activity | Lawful basis | Purpose |
|--------------------|--------------|---------|
| Account registration | Contract (Art.6(1)(b)) | Provide the service |
| Authentication / session management | Contract | Maintain login sessions |
| Audit logging (security events) | Legitimate interest (Art.6(1)(f)) | Security monitoring, incident response |
| File storage | Contract | Store user-uploaded files |
| Kafka events | Legitimate interest | Downstream processing (notifications, analytics) |

---

## 3. Data retention

| Data | Retention period | Mechanism |
|------|-----------------|-----------|
| User account | Until account deletion | `UserService.deleteUser()` |
| Refresh tokens | 7 days from issuance | `jwt.refresh-token-expiry-seconds` + `TokenCleanupScheduler` |
| Revoked refresh tokens | Until next daily purge | `TokenCleanupScheduler` (02:00 cron) |
| Audit logs (`audit.log`) | 90 days | `logback-spring.xml` rolling policy |
| Application logs | 30 days (recommendation) | Configure in deployment environment |
| S3 files | Until explicit deletion | No auto-expiry; implement lifecycle rules in S3 bucket policy |

**Token cleanup schedule:** `${token.cleanup.cron:0 0 2 * * *}` (daily at 02:00).
Override in application YAML if a shorter interval is required.

---

## 4. Right to erasure (GDPR Art.17)

When `DELETE /api/v1/users/{id}` is called by an ADMIN:

1. `UserService.deleteUser()` calls `refreshTokenRepository.deleteByUser(user)`
   **before** deleting the user row — active and revoked session tokens are removed.
2. `userRepository.delete(user)` removes the account row; the `file_metadata` rows
   linked to that user are **not** automatically deleted (files remain in S3).
3. A `USER_DELETED` audit event is written to `SECURITY_AUDIT` for traceability.

**Gaps / manual steps:**
- S3 files uploaded by the user are **not** automatically deleted.
  Implement a post-deletion job or S3 lifecycle rules keyed on `userId` prefix.
- Kafka topics retain events for the broker's configured retention period.
  If erasure of Kafka data is required, use topic compaction with tombstone records.
- Application log files may contain the deleted user's `username`.
  Log retention (90 days) provides a natural expiry.

---

## 5. Password policy

Enforced at the API boundary via Jakarta Bean Validation on `RegisterRequest`
and `UserDto`:

| Rule | Annotation | Constraint |
|------|-----------|------------|
| Required | `@NotBlank` | Cannot be blank |
| Length | `@Size(min=8, max=128)` | 8–128 characters |
| Complexity | `@Pattern` | At least one uppercase, one lowercase, one digit, one special character |

Storage: BCrypt with work factor 10 (`new BCryptPasswordEncoder()`).
Increase to 12 for higher-security deployments:
```java
new BCryptPasswordEncoder(12)   // ~300 ms per hash on modern hardware
```

---

## 6. Data in transit

| Path | Control |
|------|---------|
| Client → API | TLS (HTTPS); configure at load-balancer / ingress |
| API → PostgreSQL (QA) | `sslmode=require` in JDBC URL |
| API → PostgreSQL (prod) | `sslmode=verify-full` in JDBC URL (requires CA cert) |
| API → S3 | AWS SDK uses HTTPS by default |
| API → Kafka | Configure `security.protocol=SSL` + `ssl.keystore.*` in `application-qa/prod.yml` |

---

## 7. Data at rest

| Store | Current status | Recommendation |
|-------|---------------|----------------|
| PostgreSQL | No column-level encryption | Enable PostgreSQL TDE or use pgcrypto for `email` column |
| S3 | Not explicitly configured | Enable SSE-S3 (or SSE-KMS) in the bucket policy |
| Kafka | Not configured | Enable Kafka broker-level encryption (`log.segment.bytes` + KMS) |
| Application logs | Plain text | Restrict file permissions; route to encrypted log storage (e.g. CloudWatch, ELK with TLS) |

---

## 8. Audit trail

All security events are written to the `SECURITY_AUDIT` logger (separate
appender, 90-day retention) via `AuditService`.  Events logged:

| Event | Who / what | When |
|-------|-----------|------|
| `REGISTER_SUCCESS` | New username | Account created |
| `LOGIN_SUCCESS` / `LOGIN_FAILURE` | Username + IP | Every login attempt |
| `LOGOUT` | Username | Session voluntarily ended |
| `TOKEN_REFRESHED` / `TOKEN_REFRESH_FAILED` | Username | Token rotation |
| `TOKEN_EXPIRED` / `TOKEN_INVALID` | Username or IP | Invalid token presented |
| `USER_CREATED` / `UPDATED` / `DELETED` | Admin + target | CRUD on accounts |

**PII in logs:** only `username` (pseudonym) and IP address are logged.
Email addresses are **never** logged.

---

## 9. Consent (gap — not yet implemented)

Current status: no consent or terms-acceptance fields on the `User` entity.

Required additions for full GDPR compliance:
```sql
ALTER TABLE users ADD COLUMN terms_accepted_at  TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN privacy_accepted_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN consent_version     VARCHAR(20);
```

The registration flow (`POST /api/v1/auth/register`) must:
1. Accept a `termsVersion` and `privacyVersion` from the client.
2. Persist them with the timestamp of acceptance.
3. Refuse registration without explicit consent.

---

## 10. Subject access request (GDPR Art.15)

No dedicated SAR endpoint exists.  To fulfil a request:

```sql
SELECT id, username, email, role, enabled, created_at, updated_at
FROM   users
WHERE  email = :subject_email;

SELECT token, expires_at, revoked
FROM   refresh_tokens
WHERE  user_id = :user_id;

SELECT original_filename, s3_key, s3_bucket, content_type, file_size, uploaded_at
FROM   file_metadata
WHERE  -- add a user_id FK to file_metadata to support this query
       TRUE;
```

**Gap:** `file_metadata` has no `user_id` column — files cannot be attributed
to a subject without it.  Add `user_id BIGINT REFERENCES users(id)` to the table.

---

## 11. Third-party processors

| Processor | Data shared | Control |
|-----------|------------|---------|
| AWS S3 | File contents + filenames | DPA required; enable SSE and access logging |
| Kafka | `userId`, `username` (no email) | Internal message broker; apply encryption at rest |
| SonarCloud (CI) | Source code only (no runtime data) | No personal data shared |
