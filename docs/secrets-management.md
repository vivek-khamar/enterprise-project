# Secrets Management Strategy

## 1. Principles

| Principle | Implementation |
|-----------|---------------|
| **Never commit secrets** | `.gitignore` blocks `.env*`, `*.pem`, `*.key`, `*.jks`; `SECURITY.md` lists every known non-secret fixture |
| **Environment-variable injection** | Every secret is read from `${ENV_VAR:default}` — defaults are only safe for local dev |
| **Least privilege** | Each secret is scoped to the service that owns it; no cross-service sharing |
| **Audit every access** | The `SECURITY_AUDIT` logger records auth events, token lifecycle, and admin mutations |
| **Rotation without downtime** | Rotation runbooks are documented per secret; the application reconnects automatically where possible |

---

## 2. Secrets inventory

### HIGH risk — compromise grants persistent access

| Secret | Owner | Storage | Used by |
|--------|-------|---------|---------|
| `JWT_SECRET` | Security team | Vault / K8s Secret | `JwtUtil` — signs all access tokens |
| `DB_PASSWORD` | DBA team | Vault / K8s Secret | HikariCP — PostgreSQL connection |
| `AWS_SECRET_ACCESS_KEY` | Platform team | AWS IAM / K8s Secret | `S3Config` — S3 file operations |

### MEDIUM risk — compromise grants limited or temporary access

| Secret | Owner | Storage | Used by |
|--------|-------|---------|---------|
| `DB_USERNAME` | DBA team | Vault / K8s Secret | HikariCP |
| `AWS_ACCESS_KEY_ID` | Platform team | AWS IAM / K8s Secret | `S3Config` |
| `AWS_S3_BUCKET` | Platform team | Env var / ConfigMap | `S3Properties` |
| `SONAR_TOKEN` | CI team | GitHub Actions Secret | CI pipeline only |

### LOW risk — scoped to a single workflow run

| Secret | Owner | Storage |
|--------|-------|---------|
| `GITHUB_TOKEN` | GitHub auto-managed | GitHub Actions (expires per run) |

---

## 3. Where secrets are stored per environment

| Environment | Mechanism | Notes |
|-------------|-----------|-------|
| **Local dev** | Shell profile (`~/.zshrc`, `~/.bashrc`) or `.env` file (gitignored) | Only dev defaults; no production values |
| **CI (GitHub Actions)** | Repository Secrets (`Settings → Secrets → Actions`) | `SONAR_TOKEN` only; no DB or JWT secrets needed for tests |
| **QA** | Kubernetes Secrets (Vault-injected or `kubectl create secret`) | Injected as env vars into the Pod spec |
| **Production** | HashiCorp Vault → Kubernetes Secrets | Vault agent injects at container startup |

---

## 4. Audit logging

### What is logged

Every security-relevant event is written to the `SECURITY_AUDIT` logger (see `logback-spring.xml`). The logger writes to `logs/audit.log` in production with one JSON object per line, making each event directly indexable by Splunk / ELK / Datadog.

#### Event catalogue

| Event | Logged by | When |
|-------|-----------|------|
| `REGISTER_SUCCESS` | `AuthService.register()` | A new account is created |
| `LOGIN_SUCCESS` | `AuthService.login()` | Credentials are accepted |
| `LOGIN_FAILURE` | `AuthService.login()` | Credentials are rejected (bad password, locked account, etc.) |
| `LOGOUT` | `AuthService.logout()` | A refresh token is explicitly revoked by the client |
| `TOKEN_REFRESHED` | `AuthService.refresh()` | A valid refresh token is rotated for a new pair |
| `TOKEN_REFRESH_FAILED` | `AuthService.refresh()` | Refresh token is missing, revoked, or expired |
| `TOKEN_EXPIRED` | `JwtAuthFilter` | An access token is presented after its expiry time |
| `TOKEN_INVALID` | `JwtAuthFilter` | An access token fails signature or structural validation |
| `USER_CREATED` | `UserService.createUser()` | An admin creates a user account |
| `USER_UPDATED` | `UserService.updateUser()` | An admin updates a user account |
| `USER_DELETED` | `UserService.deleteUser()` | An admin deletes a user account |

#### Log format

```
# Structured key=value (written to console):
event=LOGIN_SUCCESS user=jsmith ip=10.0.0.1 role=USER

# JSON (written to logs/audit.log):
{"ts":"2026-06-04T09:12:34.567Z","level":"INFO","event":"LOGIN_SUCCESS","user":"jsmith","ip":"10.0.0.1","role":"USER"}
```

### What is NEVER logged

| Data | Reason |
|------|--------|
| Passwords (plaintext or hashed) | Would enable offline cracking if logs are exfiltrated |
| JWT access token values | Would allow impersonation for the token's remaining lifetime |
| Refresh token values | Would allow session hijacking |
| Full email address | PII — username is sufficient for correlation |
| AWS credentials | Obvious |

### Log retention

| Log | Retention | Justification |
|-----|-----------|---------------|
| `logs/audit.log` | 90 days on disk; indefinitely in SIEM | Regulatory compliance (PCI DSS, SOC 2) |
| Application logs | 30 days | Operational debugging |
| CI run logs | 90 days (GitHub default) | Incident investigation |

---

## 5. Rotation runbooks

### JWT_SECRET

```bash
# 1. Generate a cryptographically random 256-bit key
NEW_SECRET=$(openssl rand -base64 32)

# 2. Update the secret in Vault / Kubernetes
kubectl create secret generic jwt-secret \
  --from-literal=JWT_SECRET="$NEW_SECRET" \
  --dry-run=client -o yaml | kubectl apply -f -

# 3. Perform a rolling restart so pods pick up the new value
kubectl rollout restart deployment/enterprise-project

# 4. Clean up the refresh_tokens table — all existing tokens
#    were signed with the old key and are now invalid.
kubectl exec -it $(kubectl get pod -l app=enterprise-project -o name | head -1) \
  -- psql "$DATABASE_URL" -c "DELETE FROM refresh_tokens;"
```

**Impact:** All sessions end immediately. Users must log in again.
**Frequency:** Rotate every 90 days or immediately after suspected compromise.

### DB_PASSWORD

```bash
# 1. Rotate via PostgreSQL
psql -c "ALTER USER appuser WITH PASSWORD 'new_password';"

# 2. Update Vault / K8s secret
kubectl create secret generic db-secret \
  --from-literal=DB_PASSWORD="new_password" \
  --dry-run=client -o yaml | kubectl apply -f -

# 3. Rolling restart — HikariCP will reconnect with the new credentials
kubectl rollout restart deployment/enterprise-project
```

**Impact:** Zero downtime if rolling restart completes before HikariCP connection TTL.
**Frequency:** Rotate every 90 days.

### AWS_SECRET_ACCESS_KEY

```bash
# 1. In AWS IAM console: create a new access key for the service account
# 2. Test the new key against your S3 bucket
# 3. Update Vault / K8s secret with the new pair
# 4. Rolling restart the application
# 5. Deactivate the old access key in IAM (keep for 24h, then delete)
```

**Impact:** Zero downtime if rolled out before deleting the old key.
**Frequency:** Rotate every 90 days. AWS IAM access keys expire after 365 days.

### SONAR_TOKEN

```bash
# 1. Go to sonarcloud.io → My Account → Security → Generate Token
# 2. In GitHub: Settings → Secrets → Actions → SONAR_TOKEN → Update
# 3. No application restart needed — token is only used by CI
```

**Impact:** None on running application.
**Frequency:** Rotate annually or when a team member with access leaves.

---

## 6. CI/CD pipeline secrets handling

The GitHub Actions pipeline (`ci.yml`) follows these rules:

| Rule | How it's implemented |
|------|---------------------|
| Secrets passed via `env:` only | `SONAR_TOKEN` appears only in a step-level `env:` block, never inline in a shell command |
| No secret in command line flags | SonarCloud Maven plugin reads `SONAR_TOKEN` from the environment; not passed as `-Dsonar.login=...` |
| GitHub masks secrets in logs | GitHub Actions automatically redacts any value registered as a secret |
| Minimal scopes | `GITHUB_TOKEN` is scoped to `contents:read`, `pull-requests:write`, `checks:write`, `statuses:write` |
| Artifacts contain no secrets | Uploaded artefacts are JaCoCo HTML/XML and Surefire XML — no credentials |
| SonarCloud step skipped when absent | `if: env.SONAR_TOKEN != ''` — the build passes even without Sonar configured |

---

## 7. Developer guidelines

### DO
- ✅ Read secrets from environment variables (`System.getenv()` or Spring's `${ENV_VAR}`)
- ✅ Use `${SECRET_VAR:dev-default}` syntax — the fallback is for local dev only
- ✅ Store local secrets in `~/.zshrc` or a gitignored `.env` file
- ✅ Assume audit logs are read by the security team

### DON'T
- ❌ Commit any value that isn't safe to publish on a public GitHub gist
- ❌ Add `System.out.println(secret)` or `log.debug("token={}", jwt)` — use the event types in `AuditService.Event`
- ❌ Log request headers wholesale (`Authorization`, `Cookie`, etc.)
- ❌ Pass secrets as `-D` JVM flags (they appear in `ps aux`)
- ❌ Store secrets in `application.yml` without a `${VAR:}` wrapper

### Adding a new secret

1. Determine the risk tier (HIGH / MEDIUM / LOW) using the criteria in §2
2. Store the value in the appropriate secret manager for each environment
3. Reference it in the YAML as `${NEW_SECRET:safe-dev-default}`
4. Add an entry to the **Secrets inventory** table above
5. Write a rotation runbook in §5
6. Update `SECURITY.md` in the project root

---

## 8. Incident response

If a secret is suspected to be compromised:

1. **Rotate immediately** — follow the runbook for that secret
2. **Check audit logs** — query `SECURITY_AUDIT` for unexpected `LOGIN_SUCCESS` events or `USER_DELETED` actions from unfamiliar IPs
3. **Invalidate sessions** — truncate `refresh_tokens` for JWT, restart pods for DB/AWS
4. **Notify** — follow your organisation's security incident procedure
5. **Post-mortem** — document how the secret leaked and add a control to prevent recurrence
