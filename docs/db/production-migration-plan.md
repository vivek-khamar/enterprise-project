# Production Migration Plan
## Schema optimizations: indexes, constraints, column lengths

**Applies to:** enterprise-project (`users`) · notification-service (`notifications`)  
**Environment:** QA / Production (PostgreSQL)  
**Hibernate DDL mode:** `validate` — Hibernate will not apply any of these changes.  
**Downtime required:** None, if steps are followed in order.  
**Estimated duration:** Index builds depend on table size; see per-step estimates below.

---

## Background

`ddl-auto: validate` in QA/prod means Hibernate checks that the schema matches
the entity mapping at startup but makes no changes. All schema work must be
applied manually (or via a migration tool) before or during a deployment.

After the code change is deployed, Hibernate will expect the new constraints and
column lengths to exist. Applying the schema changes **before** the code deploy
is the safest order:

```
1. Apply schema changes (this runbook)
2. Deploy new application code
3. Verify
```

---

## Pre-flight checks

Run these before starting. They detect data violations that would cause a step to
fail mid-migration.

```sql
-- ── enterprise-project (users) ──────────────────────────────────────────────

-- Are there duplicate emails? (step EP-3 will fail if yes)
SELECT email, count(*) AS n
FROM   users
GROUP  BY email
HAVING count(*) > 1;
-- Expected: 0 rows. Resolve conflicts before proceeding.

-- Any username longer than 50 chars? (step EP-4 will reject these)
SELECT id, username, length(username) AS len
FROM   users
WHERE  length(username) > 50;
-- Expected: 0 rows.

-- Any email longer than 254 chars? (step EP-5 will reject these)
SELECT id, email, length(email) AS len
FROM   users
WHERE  length(email) > 254;
-- Expected: 0 rows.


-- ── notification-service (notifications) ────────────────────────────────────

-- Any event_type longer than 20 chars? (step NS-3 will reject these)
SELECT DISTINCT event_type, length(event_type) AS len
FROM   notifications
WHERE  length(event_type) > 20;
-- Expected: 0 rows (USER_CREATED / USER_UPDATED / USER_DELETED are all 12 chars).

-- Any username longer than 50 chars?
SELECT id, username, length(username) AS len
FROM   notifications
WHERE  length(username) > 50;
-- Expected: 0 rows.
```

If any check returns rows, resolve the data issue before continuing.

---

## Migration: enterprise-project (`users`)

### EP-1  Rename the auto-generated username unique constraint

```sql
-- Metadata-only change. No table lock. Instant.
ALTER TABLE users
    RENAME CONSTRAINT "UKr43af9ap4edm43mmtq01oddj6" TO uq_users_username;
```

> **Note:** The exact auto-generated name may differ per environment. Confirm
> with `\d users` (psql) or:
> ```sql
> SELECT conname FROM pg_constraint
> WHERE  conrelid = 'users'::regclass AND contype = 'u';
> ```

**Lock acquired:** `SHARE ROW EXCLUSIVE` for milliseconds (metadata update only).  
**Rollback:** `ALTER TABLE users RENAME CONSTRAINT uq_users_username TO "<original_name>";`

---

### EP-2  Reduce `username` column length

```sql
-- Requires pre-flight check (no username > 50 chars) to pass first.
-- PostgreSQL rewrites the table if any stored value would violate the new limit;
-- with the pre-flight check passed this is a constraint-only change — fast.
ALTER TABLE users
    ALTER COLUMN username TYPE VARCHAR(50);
```

**Lock acquired:** `ACCESS EXCLUSIVE` (blocks reads + writes) for the duration of
the column-type change. On a small `users` table this is milliseconds.  
**Rollback:** `ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(255);`

---

### EP-3  Add unique index on `email` (online, no lock)

```sql
-- CREATE INDEX CONCURRENTLY does not take a table lock.
-- The table stays fully readable and writable during the build.
-- This performs two sequential scans; duration scales with table size.
CREATE UNIQUE INDEX CONCURRENTLY idx_users_email_unique
    ON users (email);
```

> If this fails mid-build (e.g. a long-running transaction), the index is left
> in an `INVALID` state. Clean it up with:
> ```sql
> DROP INDEX CONCURRENTLY idx_users_email_unique;
> ```
> Then re-run after resolving the blocking transaction.

**Lock acquired:** None during build. A brief `SHARE UPDATE EXCLUSIVE` at the
very start and end.  
**Estimated duration:** ~1 min per 10 M rows on commodity hardware.  
**Rollback:** `DROP INDEX CONCURRENTLY idx_users_email_unique;`

---

### EP-4  Promote the index to a named constraint

```sql
-- ADD CONSTRAINT … USING INDEX converts the existing index into a constraint.
-- It does NOT rebuild the index (the work was already done in EP-3).
-- Lock duration is very short — just enough to install the constraint name.
ALTER TABLE users
    ADD CONSTRAINT uq_users_email UNIQUE USING INDEX idx_users_email_unique;
```

**Lock acquired:** `ACCESS EXCLUSIVE` for milliseconds.  
**Rollback:** `ALTER TABLE users DROP CONSTRAINT uq_users_email;`
(this drops the underlying index too — re-run EP-3 if you need it back)

---

### EP-5  Reduce `email` column length

```sql
ALTER TABLE users
    ALTER COLUMN email TYPE VARCHAR(254);
```

**Lock acquired:** `ACCESS EXCLUSIVE` for the type change. Milliseconds if the
pre-flight check confirms no values exceed 254 chars.  
**Rollback:** `ALTER TABLE users ALTER COLUMN email TYPE VARCHAR(255);`

---

### EP-6  Verify `users`

```sql
-- Confirm schema matches entity expectations
SELECT column_name, data_type, character_maximum_length, is_nullable
FROM   information_schema.columns
WHERE  table_name = 'users'
ORDER  BY ordinal_position;

-- Confirm named constraints
SELECT conname, contype
FROM   pg_constraint
WHERE  conrelid = 'users'::regclass;
-- Expected: uq_users_username (u), uq_users_email (u), users_pkey (p)
```

---

## Migration: notification-service (`notifications`)

### NS-1  Add composite index on `(user_id, created_at DESC)`

```sql
-- This is the highest-priority change. Once applied, the primary read path
-- (GET /notifications/user/{userId}) switches from a full table scan to an
-- index scan. Apply this first, independently of the other steps.
CREATE INDEX CONCURRENTLY idx_notifications_user_created
    ON notifications (user_id, created_at DESC);
```

**Lock acquired:** None during build.  
**Estimated duration:** ~2 min per 10 M rows (two sequential scans).  
**Rollback:** `DROP INDEX CONCURRENTLY idx_notifications_user_created;`

---

### NS-2  Add index on `created_at DESC`

```sql
CREATE INDEX CONCURRENTLY idx_notifications_created_at
    ON notifications (created_at DESC);
```

**Lock acquired:** None during build.  
**Estimated duration:** ~1 min per 10 M rows.  
**Rollback:** `DROP INDEX CONCURRENTLY idx_notifications_created_at;`

---

### NS-3  Reduce `event_type` column length

```sql
ALTER TABLE notifications
    ALTER COLUMN event_type TYPE VARCHAR(20);
```

**Lock acquired:** `ACCESS EXCLUSIVE` for the type change (milliseconds if
pre-flight check passed).  
**Rollback:** `ALTER TABLE notifications ALTER COLUMN event_type TYPE VARCHAR(255);`

---

### NS-4  Reduce `username` column length

```sql
ALTER TABLE notifications
    ALTER COLUMN username TYPE VARCHAR(50);
```

**Lock acquired:** `ACCESS EXCLUSIVE` for milliseconds.  
**Rollback:** `ALTER TABLE notifications ALTER COLUMN username TYPE VARCHAR(255);`

---

### NS-5  Verify `notifications`

```sql
-- Column types
SELECT column_name, data_type, character_maximum_length
FROM   information_schema.columns
WHERE  table_name = 'notifications'
ORDER  BY ordinal_position;

-- Indexes
SELECT indexname, indexdef
FROM   pg_indexes
WHERE  tablename = 'notifications';
-- Expected: notifications_pkey, idx_notifications_user_created, idx_notifications_created_at

-- Confirm the planner uses the composite index for the primary read path
EXPLAIN (ANALYZE, BUFFERS)
SELECT * FROM notifications
WHERE  user_id = 1
ORDER  BY created_at DESC;
-- Look for: "Index Scan Backward using idx_notifications_user_created"
-- If you see "Seq Scan" the table may be too small for the planner to choose the index.
-- Run: ANALYZE notifications;  then re-check.
```

---

## Locking summary

| Step | Table | Lock | Duration |
|------|-------|------|----------|
| EP-1 rename constraint | users | `SHARE ROW EXCLUSIVE` | milliseconds |
| EP-2 username length | users | `ACCESS EXCLUSIVE` | milliseconds |
| EP-3 create unique index | users | none (concurrent) | minutes |
| EP-4 promote to constraint | users | `ACCESS EXCLUSIVE` | milliseconds |
| EP-5 email length | users | `ACCESS EXCLUSIVE` | milliseconds |
| NS-1 composite index | notifications | none (concurrent) | minutes |
| NS-2 created_at index | notifications | none (concurrent) | minutes |
| NS-3 event_type length | notifications | `ACCESS EXCLUSIVE` | milliseconds |
| NS-4 username length | notifications | `ACCESS EXCLUSIVE` | milliseconds |

`ACCESS EXCLUSIVE` blocks reads and writes. All such steps here are expected to
complete in under a second on tables of any practical size (they are
constraint-metadata changes, not full rewrites). If in doubt, set a lock
timeout:

```sql
SET lock_timeout = '2s';
ALTER TABLE users ALTER COLUMN username TYPE VARCHAR(50);
RESET lock_timeout;
```

If the lock is not granted within 2 seconds the statement fails (rather than
queuing behind a long-running query and blocking all subsequent queries).

---

## Rollback procedure

If something goes wrong after deployment:

1. **Revert the code deploy** (roll back to the previous Docker image).
2. **Revert schema changes** in reverse order using the rollback SQL documented
   per step above.
3. Indexes created with `CONCURRENTLY` can be dropped with
   `DROP INDEX CONCURRENTLY` — also non-blocking.

Column-type rollbacks (`VARCHAR(20)` → `VARCHAR(255)`) are always safe because
widening a column never invalidates existing data.

---

## Monitoring during and after migration

```sql
-- Track index build progress (PostgreSQL 12+)
SELECT phase, blocks_done, blocks_total,
       round(100.0 * blocks_done / nullif(blocks_total,0), 1) AS pct
FROM   pg_stat_progress_create_index
WHERE  relid = 'notifications'::regclass;

-- After migration: confirm indexes are being used
SELECT indexrelname, idx_scan, idx_tup_read
FROM   pg_stat_user_indexes
WHERE  relname IN ('users', 'notifications')
ORDER  BY idx_scan DESC;
-- idx_notifications_user_created should accumulate scans quickly after deploy.
-- An idx_scan of 0 after a day of traffic is a warning sign.

-- Unused index candidates (re-check after 1 week of prod traffic)
SELECT relname, indexrelname, idx_scan
FROM   pg_stat_user_indexes
JOIN   pg_index USING (indexrelid)
WHERE  indisunique = false
  AND  idx_scan < 10
ORDER  BY idx_scan;
```

---

## Next steps: migrate to Flyway

The `db/migration/V1__initial_schema.sql` files in each service are already
named for Flyway. To wire it up:

```xml
<!-- pom.xml -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

```yaml
# application-qa.yml / application-prod.yml
spring:
  flyway:
    enabled: true
    locations: classpath:db/migration
  jpa:
    hibernate:
      ddl-auto: none   # Flyway owns the schema; Hibernate only validates
```

With Flyway in place, future migrations are versioned, checksummed, and applied
atomically at startup — this runbook becomes unnecessary for new changes.
