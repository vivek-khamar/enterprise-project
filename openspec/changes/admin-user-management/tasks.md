## 1. DTOs

- [x] 1.1 Create `AdminUserDto` record with fields: `id`, `username`, `email`, `role`, `enabled`, `createdAt`; add a static factory `AdminUserDto.from(User)` method
- [x] 1.2 Create `UpdateStatusRequest` DTO with a single `boolean enabled` field and `@NotNull` validation
- [x] 1.3 Create `UpdateRoleRequest` DTO with a single `Role role` field and `@NotNull` validation

## 2. Audit Events

- [x] 2.1 Add `USER_ENABLED`, `USER_DISABLED`, and `ROLE_CHANGED` to `AuditService.Event` enum

## 3. Service Layer

- [x] 3.1 Add `UserService.enableDisableUser(Long id, boolean enabled, String adminUsername)`: load user by ID (404 if missing), guard against self-disable, set `enabled`, save, publish `UserEvent`, log audit event
- [x] 3.2 Add `UserService.changeRole(Long id, Role newRole, String adminUsername)`: load user by ID (404 if missing), guard against self-role-change, set role, save, publish `UserEvent`, log `ROLE_CHANGED` audit event
- [x] 3.3 Update `UserService.getUser(Long id)` and `UserService.getAllUsers(...)` return types to `AdminUserDto` using the factory method from task 1.1

## 4. Controller

- [x] 4.1 Add `PATCH /api/v1/users/{id}/status` endpoint to `UserController`: extract authenticated principal name, delegate to `UserService.enableDisableUser`, return `AdminUserDto` with HTTP 200
- [x] 4.2 Add `PATCH /api/v1/users/{id}/role` endpoint to `UserController`: extract authenticated principal name, delegate to `UserService.changeRole`, return `AdminUserDto` with HTTP 200
- [x] 4.3 Update `UserController.getUserById` and `UserController.getAllUsers` (including search) to return `AdminUserDto`

## 5. Exception Handling

- [x] 5.1 Create `SelfModificationException` (extends `RuntimeException`) for the self-disable / self-role-change guard with message "Cannot modify your own account status or role"
- [x] 5.2 Add `handleSelfModificationException` handler to `GlobalExceptionHandler` returning HTTP 400

## 6. Tests

- [x] 6.1 Update `UserControllerTest` to expect `AdminUserDto` shape (add `role`, `enabled`, `createdAt` assertions) on existing get/list/search tests
- [x] 6.2 Add `UserControllerTest` cases for `PATCH /status`: admin disables user (200), admin enables user (200), non-admin rejected (403), self-disable rejected (400), not-found (404)
- [x] 6.3 Add `UserControllerTest` cases for `PATCH /role`: admin promotes (200), admin demotes (200), non-admin rejected (403), self-role-change rejected (400), invalid role (400), not-found (404)
- [x] 6.4 Update `UserServiceTest` to cover `enableDisableUser` and `changeRole` (happy path, self-guard, not-found)
- [x] 6.5 Add `SecurityValidationTest` cases confirming `PATCH /status` and `PATCH /role` require ADMIN role
- [x] 6.6 `SelfModificationException` handler added to `GlobalExceptionHandler`; `LoggingEmailServiceTest` already exists from prior CI fix

## 7. Verify

- [x] 7.1 Run `./mvnw clean verify` and confirm all tests pass and coverage gate (≥ 80 %) is met
