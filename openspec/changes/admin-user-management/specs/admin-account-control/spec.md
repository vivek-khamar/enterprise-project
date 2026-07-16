## ADDED Requirements

### Requirement: Admin can disable a user account
The system SHALL allow ADMIN-role callers to disable any user account by ID via `PATCH /api/v1/users/{id}/status`. A disabled user SHALL be unable to authenticate. The response SHALL return HTTP 200 with the updated `AdminUserDto`. Attempting to disable a non-existent user SHALL return HTTP 404. Attempting to disable the currently authenticated admin SHALL return HTTP 400.

#### Scenario: Admin disables an active user
- **WHEN** `PATCH /api/v1/users/{id}/status` is called by an ADMIN with body `{"enabled": false}` and the user exists and is currently active
- **THEN** the system sets `enabled = false`, logs a `USER_DISABLED` audit event, and returns HTTP 200 with the updated `AdminUserDto` containing `"enabled": false`

#### Scenario: Admin enables a disabled user
- **WHEN** `PATCH /api/v1/users/{id}/status` is called by an ADMIN with body `{"enabled": true}` and the user exists and is currently disabled
- **THEN** the system sets `enabled = true`, logs a `USER_ENABLED` audit event, and returns HTTP 200 with the updated `AdminUserDto` containing `"enabled": true`

#### Scenario: Disabled user cannot authenticate
- **WHEN** `POST /api/v1/auth/login` is called with valid credentials for a disabled user
- **THEN** the system returns HTTP 401

#### Scenario: Non-admin status change rejected
- **WHEN** `PATCH /api/v1/users/{id}/status` is called by a USER-role caller
- **THEN** the system returns HTTP 403

#### Scenario: Status change on non-existent user returns 404
- **WHEN** `PATCH /api/v1/users/{id}/status` is called with an ID that does not exist
- **THEN** the system returns HTTP 404

#### Scenario: Admin cannot disable themselves
- **WHEN** `PATCH /api/v1/users/{id}/status` is called by an ADMIN with `{"enabled": false}` where `{id}` is the ID of the authenticated admin
- **THEN** the system returns HTTP 400 with a descriptive error message

---

### Requirement: Admin can change a user's role
The system SHALL allow ADMIN-role callers to change the role of any user (other than themselves) via `PATCH /api/v1/users/{id}/role`. The new role MUST be a valid `Role` enum value (`USER` or `ADMIN`). The response SHALL return HTTP 200 with the updated `AdminUserDto`. An admin changing their own role SHALL return HTTP 400.

#### Scenario: Admin promotes a user to ADMIN
- **WHEN** `PATCH /api/v1/users/{id}/role` is called by an ADMIN with body `{"role": "ADMIN"}` and the target user exists
- **THEN** the system sets the user's role to `ADMIN`, logs a `ROLE_CHANGED` audit event, and returns HTTP 200 with the updated `AdminUserDto` containing `"role": "ADMIN"`

#### Scenario: Admin demotes an ADMIN to USER
- **WHEN** `PATCH /api/v1/users/{id}/role` is called by an ADMIN with body `{"role": "USER"}` and the target user exists and has role `ADMIN`
- **THEN** the system sets the user's role to `USER`, logs a `ROLE_CHANGED` audit event, and returns HTTP 200 with the updated `AdminUserDto` containing `"role": "USER"`

#### Scenario: Non-admin role change rejected
- **WHEN** `PATCH /api/v1/users/{id}/role` is called by a USER-role caller
- **THEN** the system returns HTTP 403

#### Scenario: Role change on non-existent user returns 404
- **WHEN** `PATCH /api/v1/users/{id}/role` is called with an ID that does not exist
- **THEN** the system returns HTTP 404

#### Scenario: Admin cannot change their own role
- **WHEN** `PATCH /api/v1/users/{id}/role` is called by an ADMIN where `{id}` is the ID of the authenticated admin
- **THEN** the system returns HTTP 400 with a descriptive error message

#### Scenario: Invalid role value rejected
- **WHEN** `PATCH /api/v1/users/{id}/role` is called with a body containing an unrecognised role value
- **THEN** the system returns HTTP 400
