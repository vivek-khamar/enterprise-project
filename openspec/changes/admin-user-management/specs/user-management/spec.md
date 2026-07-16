## MODIFIED Requirements

### Requirement: Authenticated user can list users with pagination
The system SHALL return a paginated list of all users when no filter parameters are supplied. The default page size SHALL be 50, sorted ascending by ID. Each item in the page SHALL be returned as an `AdminUserDto` containing `id`, `username`, `email`, `role`, `enabled`, and `createdAt`.

#### Scenario: List all users returns paginated result with AdminUserDto fields
- **WHEN** GET `/api/v1/users` is called by any authenticated user with no query parameters
- **THEN** the system returns HTTP 200 with a `Page<AdminUserDto>` containing up to 50 results, each including `role`, `enabled`, and `createdAt`

#### Scenario: Custom page and size parameters are honoured
- **WHEN** GET `/api/v1/users?page=1&size=10` is called
- **THEN** the system returns the second page with up to 10 results

---

### Requirement: Authenticated user can get a user by ID
The system SHALL return a single `AdminUserDto` when given a valid, existing user ID. The response SHALL include `id`, `username`, `email`, `role`, `enabled`, and `createdAt`.

#### Scenario: Get existing user returns 200 with AdminUserDto
- **WHEN** GET `/api/v1/users/{id}` is called with a valid ID
- **THEN** the system returns HTTP 200 with the matching `AdminUserDto` including `role`, `enabled`, and `createdAt`

#### Scenario: Non-existent user returns 404
- **WHEN** GET `/api/v1/users/{id}` is called with an ID that does not exist
- **THEN** the system returns HTTP 404

#### Scenario: Non-positive ID rejected
- **WHEN** GET `/api/v1/users/{id}` is called with a zero or negative ID
- **THEN** the system returns HTTP 400

---

### Requirement: Authenticated user can search users by username and/or email
The system SHALL support optional `username` and `email` query parameters. When either is present, the system SHALL return only users whose fields match (case-insensitive partial match). Results SHALL be returned as `AdminUserDto`.

#### Scenario: Search by username returns AdminUserDto
- **WHEN** GET `/api/v1/users?username=jsmith` is called
- **THEN** the system returns only users whose username contains "jsmith" (case-insensitive), each as an `AdminUserDto`

#### Scenario: Search by email
- **WHEN** GET `/api/v1/users?email=example.com` is called
- **THEN** the system returns only users whose email contains "example.com"

#### Scenario: Blank filter treated as no filter
- **WHEN** GET `/api/v1/users?username=` is called with a blank username
- **THEN** the system returns all users (treats the parameter as absent)
