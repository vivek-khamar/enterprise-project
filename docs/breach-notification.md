# Data Breach Notification Process

## Legal basis

| Regulation | Obligation | Deadline |
|------------|-----------|----------|
| **GDPR Art.33** | Notify the supervisory authority (DPA) of a personal-data breach | **72 hours** from becoming aware |
| **GDPR Art.34** | Notify affected data subjects when the breach is likely to result in a high risk | Without undue delay |
| **GDPR Art.30** | Record the breach in the internal register of processing activities | No fixed deadline; contemporaneous |

A **personal-data breach** is any breach of security leading to accidental or
unlawful destruction, loss, alteration, unauthorised disclosure of, or access to,
personal data (GDPR Art.4(12)).

---

## 1. What counts as a breach in this system

The system stores the following personal data (full inventory in
[docs/data-protection.md](data-protection.md)):

| Data | Breach scenario |
|------|----------------|
| User `email` and `username` | Unauthorised DB read / SQL dump / misconfigured API response |
| BCrypt password hashes | DB exfiltration (hashes must still be treated as sensitive) |
| Refresh tokens (`refresh_tokens` table) | Token theft enabling session hijacking |
| Uploaded files (`file_metadata` + S3) | Misconfigured S3 ACL / presigned-URL leakage |
| Access tokens (JWT) | Log exposure, network interception, XSS exfiltration |
| Audit log lines (username + IP) | Log file exfiltration |

**Not classified as a breach on their own:**
- A single failed login by the legitimate user.
- Rate-limiting a single IP — expected behaviour.
- An expired or revoked token being rejected normally.

---

## 2. Detection signals from the audit log

All security events are written to `logs/audit.log` in JSON format
(one object per line).  The following patterns indicate a potential breach.

### 2.1 Query commands

Run these against the audit log to surface incidents.

```bash
AUDIT_LOG=/var/log/enterprise/audit.log   # adjust to your deployment path

# --- Brute-force / credential stuffing ---
# 10+ LOGIN_FAILURE from the same IP in 1 hour
jq -r 'select(.event=="LOGIN_FAILURE") | .ip' "$AUDIT_LOG" \
  | sort | uniq -c | sort -rn | awk '$1 >= 10'

# --- Distributed password spray ---
# Many unique IPs failing login to the SAME user
jq -r 'select(.event=="LOGIN_FAILURE") | "\(.user) \(.ip)"' "$AUDIT_LOG" \
  | awk '{print $1}' | sort | uniq -c | sort -rn | awk '$1 >= 5'

# --- Token replay / theft ---
# TOKEN_INVALID or TOKEN_REFRESH_FAILED spikes (possible stolen token attempts)
jq -r 'select(.event | test("TOKEN_INVALID|TOKEN_REFRESH_FAILED"))' "$AUDIT_LOG" | wc -l

# --- Bulk account deletion (insider threat / privilege abuse) ---
# More than 3 USER_DELETED events from the same admin in 1 hour
jq -r 'select(.event=="USER_DELETED") | .admin' "$AUDIT_LOG" \
  | sort | uniq -c | sort -rn | awk '$1 >= 3'

# --- Login from unexpected IP after long gap (account takeover) ---
# Review LOGIN_SUCCESS events for users who haven't logged in recently
jq -r 'select(.event=="LOGIN_SUCCESS") | "\(.ts) \(.user) \(.ip)"' "$AUDIT_LOG" | tail -100

# --- Rate limit breached (automated attack) ---
jq -r 'select(.event=="RATE_LIMIT_EXCEEDED") | .ip' "$AUDIT_LOG" \
  | sort | uniq -c | sort -rn | head -20
```

### 2.2 Severity classification

| Pattern | Likely breach type | Severity |
|---------|------------------|----------|
| Continuous `LOGIN_FAILURE` from single IP | Brute force | Medium |
| `LOGIN_FAILURE` across many IPs for same user | Credential stuffing | High |
| `TOKEN_INVALID` volume spike | Stolen token replay | High |
| `USER_DELETED` bulk by one admin | Insider threat | Critical |
| `LOGIN_SUCCESS` from unfamiliar geography | Account takeover | High |
| No `LOGOUT` before `TOKEN_REFRESH_FAILED` | Session hijacking | High |
| Application logs contain raw DB query results | Data exfiltration | Critical |

---

## 3. Response phases

### Phase 0 — Triage (0–2 hours)

**Who:** On-call engineer + security lead.

1. Confirm the alert is not a false positive.
2. Determine the **type** of breach (credential, session, data dump, insider).
3. Estimate **scope** — how many records, which users, which data categories.
4. Assign a breach severity: **Low / Medium / High / Critical**.
5. Open an internal incident ticket and assign an incident commander.

### Phase 1 — Containment (0–4 hours)

| Action | Command / instruction |
|--------|----------------------|
| Revoke all refresh tokens for affected users | `DELETE FROM refresh_tokens WHERE user_id IN (...);` |
| Invalidate the JWT signing key | Rotate `JWT_SECRET` (see [docs/secrets-management.md](secrets-management.md)) |
| Block attacker IPs | Add to WAF / ingress deny list |
| Disable affected accounts | `UPDATE users SET enabled = false WHERE id IN (...);` |
| Rotate compromised credentials | Follow per-secret runbook in secrets-management.md |
| Enable enhanced audit logging | Increase log verbosity; forward to SIEM if not already |

### Phase 2 — Assessment (2–24 hours)

1. Collect all relevant log lines from `audit.log` and application logs.
2. Determine the **root cause** (e.g. misconfigured endpoint, stolen credentials, insider).
3. Enumerate **affected records** — export the user list.
4. Assess the **risk to data subjects** (GDPR Art.34 threshold):
   - Would a reasonable person consider this high-risk?
   - Does it involve passwords, financial data, health data, or sensitive categories?
   - Could the data be used to discriminate, defraud, or harm subjects?
5. Document findings in the internal incident register.

### Phase 3 — GDPR notification (within 72 hours of awareness)

#### 3a. Supervisory authority (GDPR Art.33)

Notify the DPA **even if all the facts are not yet known**.
An initial notification can be supplemented later.

**Content required (Art.33(3)):**
- Nature of the breach (type, categories of data, approximate number of records/subjects)
- Name and contact details of the Data Protection Officer (or other contact)
- Likely consequences of the breach
- Measures taken or proposed

**UK:** [ICO report a breach portal](https://ico.org.uk/for-organisations/report-a-breach/)  
**EU (example — Ireland):** [DPC breach notification](https://www.dataprotection.ie/en/organisations/notifying-data-breach)  
**Template:** see §5 below.

#### 3b. Data subjects (GDPR Art.34)

Required **only** when the breach is likely to result in **high risk** to the
rights and freedoms of the affected individuals.

Not required if:
- Data was encrypted with an uncompromised key.
- Measures have been taken to ensure risk is unlikely to materialise.
- Notification would require disproportionate effort (use public communication instead).

**Template:** see §5 below.

### Phase 4 — Remediation (days 1–14)

1. Deploy the fix that prevented/would have prevented the breach.
2. Issue a security patch release.
3. Re-run the full test suite and security audit.
4. Rotate all secrets as a precaution (even those not directly compromised).
5. Update [docs/secrets-management.md](secrets-management.md) with any new rotation steps.

### Phase 5 — Post-incident review (within 30 days)

1. Root-cause analysis (5 Whys or fault tree).
2. Update threat model.
3. Add regression tests covering the exploited vector.
4. Update this document if the process was deficient.
5. Brief the team; no blame, focus on systemic improvements.

---

## 4. Internal incident register (GDPR Art.30(5))

Maintain a record of every breach regardless of whether Art.33 notification
was required.  Minimum fields:

| Field | Content |
|-------|---------|
| Incident ID | INC-YYYY-NNN |
| Date/time detected | ISO-8601 |
| Date/time contained | ISO-8601 |
| Nature of breach | e.g. "unauthorised read of users table" |
| Data categories affected | e.g. username, email, password hash |
| Approximate number of records | e.g. "~250 user accounts" |
| Approximate number of subjects | — |
| Root cause | Brief description |
| Consequences | Actual or likely impact |
| Measures taken | Containment + remediation actions |
| DPA notified? | Yes / No / N/A (reason) |
| Subjects notified? | Yes / No / N/A (reason) |
| DPO sign-off | Name + date |

Store this register securely (not in the public source repository).

---

## 5. Notification templates

### 5a. DPA notification (Art.33)

```
SUBJECT: Personal Data Breach Notification — [Organisation] — INC-YYYY-NNN

To the [Supervisory Authority],

We are writing to notify you of a personal data breach in accordance with
Article 33 of the GDPR.

1. NATURE OF THE BREACH
   [Describe the incident: e.g. "Unauthorised access to our user database via
   a compromised admin credential, resulting in exposure of email addresses
   and BCrypt password hashes for approximately N users."]

2. DATA CATEGORIES AFFECTED
   [List: e.g. email address, username, BCrypt password hash, IP addresses
   from audit logs]

3. APPROXIMATE NUMBER OF DATA SUBJECTS AFFECTED
   [e.g. "Approximately 250 registered users"]

4. CONTACT DETAILS
   Data Protection Officer (or contact): [Name, email, phone]

5. LIKELY CONSEQUENCES
   [e.g. "Affected users may receive phishing emails or experience credential
   stuffing on other services where they reuse passwords."]

6. MEASURES TAKEN OR PROPOSED
   - Revoked all active session tokens for affected users.
   - Rotated JWT signing key and database credentials.
   - Notified affected users and advised password change.
   - Patched the vulnerability (deployment in progress).
   - Enhanced monitoring deployed.

We will provide a follow-up notification within [X] days with a full root-cause
analysis and final impact assessment.

[Name], [Title]
[Organisation]
[Date]
```

### 5b. Data subject notification (Art.34)

```
SUBJECT: Important: Security Notice for Your [App Name] Account

Dear [username / "user"],

We are writing to inform you of a security incident that may have affected
your account.

WHAT HAPPENED
On [date], we detected that [brief, plain-language description, e.g.
"an unauthorised party accessed our database and may have obtained email
addresses and password hashes for a number of accounts, including yours."]

WHAT INFORMATION WAS INVOLVED
The following information relating to your account may have been affected:
- Email address
- Encrypted password (BCrypt hash — your password was not stored in plain text)

WHAT WE HAVE DONE
- We have immediately revoked all active login sessions.
- We have rotated our authentication keys.
- We have applied additional security controls to prevent recurrence.

WHAT YOU SHOULD DO
1. Change your password on [App Name] immediately at [password-reset-URL].
2. If you use the same password on other services, change it there too.
3. Enable two-factor authentication where available.
4. Be alert to phishing emails — we will never ask for your password by email.

CONTACT
If you have any questions, please contact [support@example.com].

We sincerely apologise for this incident and the concern it may cause.

[Organisation name]
[Date]
```

---

## 6. Contacts and escalation path

Fill in these details before going live:

| Role | Name | Contact |
|------|------|---------|
| Incident commander (on-call) | TBD | Pager / Slack |
| Security lead | TBD | TBD |
| Data Protection Officer | TBD | TBD |
| Legal counsel | TBD | TBD |
| PR / communications | TBD | TBD |
| Supervisory authority | ICO (UK) / DPC (IE) / etc. | See §3a links |

---

## 7. Drill schedule

Conduct a tabletop breach simulation at least once per year:
- Choose one scenario from §2.2 (e.g. credential stuffing resulting in bulk login).
- Walk through phases 0–3 using the query commands in §2.1.
- Time each phase against the deadlines.
- Identify gaps and update this document.
