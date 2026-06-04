# Security Policy

## Reporting a vulnerability

Do **not** open a public GitHub issue for security vulnerabilities.
Email the maintainer directly or use GitHub's private vulnerability reporting:
**Settings → Code security and analysis → Report a vulnerability**.

---

## Secrets inventory

Every secret the project uses is listed here.
All are injected at runtime via environment variables — **none are hardcoded in source**.

| Secret | Where it's used | How to set it |
|--------|----------------|---------------|
| `JWT_SECRET` | Signs and verifies all JWT access tokens (`JwtUtil`) | `export JWT_SECRET=$(openssl rand -base64 32)` |
| `DB_USERNAME` | Connects to QA/prod PostgreSQL | Set in the deployment environment |
| `DB_PASSWORD` | Connects to QA/prod PostgreSQL | Set in the deployment environment |
| `AWS_ACCESS_KEY_ID` | S3 file storage (`S3Config`) | Set in the deployment environment |
| `AWS_SECRET_ACCESS_KEY` | S3 file storage (`S3Config`) | Set in the deployment environment |
| `AWS_S3_BUCKET` | S3 bucket name | Set in the deployment environment |
| `SONAR_TOKEN` | CI-only: SonarCloud analysis | GitHub repo → Settings → Secrets → Actions |

### Development defaults

| Secret | Dev default | Safe? |
|--------|-------------|-------|
| `JWT_SECRET` | Base64("enterprise-project-jwt-secret-key-for-development") | Dev/CI only — **never use in production** |
| `DB_*` | H2 in-memory (`sa` / empty password) | Dev only — not accessible remotely |
| `AWS_*` | Empty string → uses `DefaultCredentialsProvider` | Harmless if no IAM credentials exist |

The integration-test profile uses a separate test-only JWT secret
(`application-integration-test.yml`) that is committed because it is never used outside tests.

---

## What the CI/CD pipeline does with secrets

The GitHub Actions workflow (`.github/workflows/ci.yml`) handles secrets as follows:

| Secret | Mechanism | Risk of exposure |
|--------|-----------|-----------------|
| `GITHUB_TOKEN` | GitHub auto-injects; scoped to `contents:read`, `pull-requests:write`, `checks:write`, `statuses:write` | Expires at end of each run; cannot be read from logs |
| `SONAR_TOKEN` | Stored as GitHub Actions secret; passed to the step's `env:` block only — **not inlined into the `run:` shell command** | GitHub automatically masks it in logs; step is skipped if the secret is absent |

Nothing in the pipeline:
- Echoes (`echo`) or prints a secret value
- Stores secrets in build artifacts (JaCoCo HTML/XML and Surefire XML contain no credentials)
- Passes secrets as `-D` properties on the command line (which could appear in process listings)

---

## Rotation procedures

### JWT_SECRET

```bash
# 1. Generate a new key
NEW_SECRET=$(openssl rand -base64 32)

# 2. Update the secret in your deployment environment
#    (Kubernetes secret, AWS Parameter Store, Vault, etc.)

# 3. Restart the application — all previously issued tokens become invalid.
#    Users will be logged out and must re-authenticate.
```

Refresh tokens stored in the `refresh_tokens` table will also become invalid after rotation
(they are verified using the access-token signing key via the stateless JWT verification path).
Clear the `refresh_tokens` table after rotation to avoid confusing error messages.

### Database credentials

Follow your PostgreSQL provider's documented credential rotation guide.
Update `DB_USERNAME` / `DB_PASSWORD` in the deployment environment and restart the application.
HikariCP will reconnect using the new credentials on the next connection attempt or pool refresh.

### AWS S3 credentials

Rotate via the AWS IAM console.
Update `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` in the deployment environment.
No application restart is required — the `DefaultCredentialsProvider` refreshes credentials automatically.

---

## Known intentional non-secrets in source

| Value | File | Reason it's not a secret |
|-------|------|--------------------------|
| `$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy` | `entity/User.java` | BCrypt hash of the word "password"; used only as a placeholder for test fixtures that do not exercise authentication |
| `dGVzdC1qd3Qtc2VjcmV0LWtleS1mb3ItaW50ZWdyYXRpb24tdGVzdHM=` | `src/test/resources/application-integration-test.yml` | Test-only JWT key; the integration tests run in an isolated H2/TestContainers environment that is never exposed externally |
