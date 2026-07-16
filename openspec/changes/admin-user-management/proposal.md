## Why

Admins currently have no API to control account lifecycle (enable/disable) or change a user's role after registration — both are day-one operational needs for any managed SaaS. Without these endpoints, account lockout and privilege escalation/demotion must be done directly in the database.

## What Changes

- New `PATCH /api/v1/users/{id}/status` endpoint: admin can enable or disable a user account
- New `PATCH /api/v1/users/{id}/role` endpoint: admin can promote a user to ADMIN or demote to USER
- `User` entity gains an `enabled` field (already present in the entity; just needs to be surfaced via the management API)
- Disabled users are rejected at authentication time (Spring Security `UserDetails.isEnabled()`)
- New DTOs: `UpdateStatusRequest`, `UpdateRoleRequest`, `AdminUserDto` (extends `UserDto` with `role`, `enabled`, `createdAt`)
- Admin list/get endpoints return `AdminUserDto` instead of the basic `UserDto`, making role and status visible
- Audit log entries for status changes and role changes

## Capabilities

### New Capabilities
- `admin-account-control`: Enable/disable user accounts and change user roles via dedicated PATCH endpoints, with full audit trail

### Modified Capabilities
- `user-management`: Admin list and get-by-ID responses now return `AdminUserDto` (adds `role`, `enabled`, `createdAt` fields); this is an additive change but constitutes a spec-level response shape update

## Impact

- **API**: 2 new endpoints (`PATCH /api/v1/users/{id}/status`, `PATCH /api/v1/users/{id}/role`)
- **Entity**: `User.enabled` already exists; `User.role` already exists — no schema migration needed
- **Security**: Disabled user check in `UserDetailsServiceImpl` (`isEnabled()` already part of Spring Security contract)
- **Audit**: `AuditService` extended with `USER_DISABLED`, `USER_ENABLED`, `ROLE_CHANGED` event types
- **Tests**: New controller tests, service tests, and security tests for the new endpoints
- **Existing behaviour**: All current `user-management` endpoints are unchanged except enriched response shape for admin callers
