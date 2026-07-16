## Context

The codebase already has the infrastructure needed for this feature:
- `User.enabled` and `User.role` fields exist on the JPA entity with correct column mappings
- `UserDetailsServiceImpl` already propagates `enabled` to Spring Security (`disabled(!user.isEnabled())` + `accountLocked(!user.isEnabled())`), so disabling a user immediately blocks login and JWT acceptance on the next request
- `AuditService` exists with admin event types (`USER_CREATED`, `USER_UPDATED`, `USER_DELETED`); it needs two new events
- `UserController` at `/api/v1/users` handles existing CRUD; the new endpoints extend it
- Role enum is `com.enterprise.demo.security.Role { USER, ADMIN }`

The only gaps are: two new PATCH endpoints, the corresponding service methods, request/response DTOs, new audit events, and tests.

## Goals / Non-Goals

**Goals:**
- `PATCH /api/v1/users/{id}/status` — admin enables or disables a user account
- `PATCH /api/v1/users/{id}/role` — admin changes a user's role
- Admin list (`GET /api/v1/users`) and get-by-ID (`GET /api/v1/users/{id}`) return `AdminUserDto` (adds `role`, `enabled`, `createdAt`) so admins can see account state without extra calls
- Audit trail: `USER_ENABLED`, `USER_DISABLED`, `ROLE_CHANGED` events logged via `AuditService`
- Full test coverage: controller (MockMvc), service (Mockito), security validation

**Non-Goals:**
- Bulk enable/disable (single-resource operations are sufficient for now)
- Self-service role escalation (users cannot change their own role)
- Soft-delete or account suspension with reason tracking
- UI / admin dashboard frontend

## Decisions

### Decision 1 — PATCH on sub-resources, not PUT on the full user

Using dedicated `PATCH /users/{id}/status` and `PATCH /users/{id}/role` endpoints rather than extending the existing `PUT /users/{id}` payload.

**Why:** Keeps payloads minimal and intent explicit; avoids accidental field overwrite on a full PUT. Role and status changes are security-sensitive operations that benefit from discrete audit events and permission checks.

**Alternative considered:** Adding `enabled` and `role` to `UpdateUserRequest`. Rejected because it conflates the semantics of profile editing (done by admin for data corrections) with security operations (role assignment, account lock-out).

### Decision 2 — `AdminUserDto` for admin callers, `UserDto` for regular callers

Introduce a new `AdminUserDto` record that extends the basic `UserDto` fields with `role`, `enabled`, and `createdAt`. The existing `UserController` methods return `AdminUserDto` (same JSON shape for all callers — non-admin callers can also see these fields, which is acceptable since role/enabled is not sensitive read data).

**Why:** Simpler than having conditional response shapes. The additional fields are harmless to expose to authenticated non-admins (read-only, no PII beyond what they already see). Adding them to a single DTO avoids a content-negotiation or projection mechanism.

**Alternative considered:** Different response types per role using Spring Security `@PostFilter`. Rejected as over-engineering for the current scale.

### Decision 3 — Status and role mutations live in `UserService`, not a new service

`UserService` already owns user entity mutations (`createUser`, `updateUser`, `deleteUser`). Adding `enableUser`, `disableUser`, and `changeRole` there keeps the mutation boundary consistent and reuses the existing `@Transactional` / `UserEventPublisher` infrastructure.

**Why:** A separate `AdminUserService` would add a layer with no real encapsulation benefit at this scale.

### Decision 4 — Disable check happens at authentication (already wired), not on every request

`UserDetailsServiceImpl` sets `disabled(!user.isEnabled())` so Spring Security rejects authentication for disabled users. Existing JWT tokens for a disabled user will be rejected on the next request when `UserDetailsServiceImpl.loadUserByUsername` is called (Spring Security checks `isEnabled()` on the loaded `UserDetails`).

**Why:** Zero additional filter code needed. JWTs are short-lived (15 min), so the window between disabling a user and full enforcement is bounded.

**Alternative considered:** Revoking all existing tokens on disable. Rejected as premature — token revocation infrastructure (blocklist) is not yet built.

## Risks / Trade-offs

- **[Risk] Short window before disabled user's JWT expires** → Mitigation: acceptable given 15-minute access token TTL; document as known limitation. Full revocation can be added when the token blocklist feature is built.
- **[Risk] Admin demotes themselves to USER** → Mitigation: add a guard in `UserService.changeRole` that prevents changing the role of the currently authenticated principal.
- **[Risk] `AdminUserDto` exposes `createdAt` to non-admin callers** → Not a real risk; `createdAt` is not sensitive. If requirements change, a projection strategy can be introduced later.

## Migration Plan

No database schema changes — `enabled` and `role` columns already exist. No data migration needed. Deploy as a standard rolling update.

Rollback: the two new endpoints can be removed without any schema rollback.

## Open Questions

- Should `GET /api/v1/users` for a USER-role caller return `UserDto` (current shape) or `AdminUserDto` (with role/enabled)? Current decision: always return `AdminUserDto` for simplicity. Re-evaluate if PII/role exposure becomes a compliance concern.
- Should an admin be able to create another ADMIN via `POST /api/v1/users`? Currently out of scope — new users are always created as `USER` role; role escalation goes through `PATCH /role`.
