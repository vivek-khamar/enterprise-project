# CLAUDE.md — Enterprise Project

## Architecture Overview

Single-service Spring Boot REST API following a layered architecture:

```
Controller → Service → Repository → Database
                ↑               ↓
              DTOs / Validation  UserEventPublisher → Kafka ("user-events")
                                                            ↓
                                                  UserEventConsumer (logs)
```

- No microservices yet — monolith with future expansion placeholders (`security/`, `util/` packages)
- H2 in-memory DB for local dev; PostgreSQL for QA and production
- Kafka event bus integrated: every user mutation (create/update/delete) publishes a `UserEvent` to the `user-events` topic

## Tech Stack

| Layer        | Technology                      | Version    |
|--------------|---------------------------------|------------|
| Language     | Java                            | 25         |
| Framework    | Spring Boot (webmvc)            | 4.0.6      |
| Persistence  | Spring Data JPA + Hibernate     | Boot-managed |
| Database     | H2 (dev), PostgreSQL (qa/prod)  | —          |
| Validation   | Jakarta Bean Validation         | Boot-managed |
| Monitoring   | Spring Boot Actuator            | Boot-managed |
| Messaging    | Spring Kafka                    | Boot-managed |
| Build        | Maven (wrapper: `./mvnw`)       | —          |
| Boilerplate  | Lombok                          | Boot-managed |

Spring Boot 4.x uses `jakarta.*` namespaces (not `javax.*`). Use `spring-boot-starter-webmvc`, not the older `spring-boot-starter-web`.

## Running Locally

```bash
./mvnw spring-boot:run                          # dev profile active by default
./mvnw spring-boot:run -Dspring-boot.run.profiles=qa
```

- API base: `http://localhost:8080/enterprise/api/v1/`
- H2 console: `http://localhost:8080/enterprise/h2-console/` (JDBC URL: `jdbc:h2:mem:testdb`, user: `sa`)

## Profiles & Configuration

| Profile | Database          | DDL          | Log level         |
|---------|-------------------|--------------|-------------------|
| `dev`   | H2 in-memory      | `update`     | DEBUG             |
| `qa`    | PostgreSQL (qa-db-server:5432) | `validate` | INFO |
| `prod`  | PostgreSQL (prod-db-server:5432) | `validate` | WARN |

Production DB credentials must be provided via env vars `DB_USERNAME` / `DB_PASSWORD`. QA credentials are currently hardcoded in `application-qa.yml` — treat as technical debt.

## API Contracts

Base path: `/enterprise/api/v1/users`

| Method | Path       | Description           |
|--------|------------|-----------------------|
| GET    | `/`        | List all users        |
| GET    | `/{id}`    | Get user by ID        |
| POST   | `/`        | Create user           |
| PUT    | `/{id}`    | Update user           |
| DELETE | `/{id}`    | Delete user           |

**UserDto** (request/response body):
```json
{ "id": 1, "username": "jsmith", "email": "j@example.com" }
```
- `username`: required, non-blank
- `email`: required, valid email format

Error responses use a structured envelope with `timestamp`, `message`, and `details` fields. `ResourceNotFoundException` → 404; unhandled exceptions → 500.

No API documentation (Swagger/OpenAPI) is configured yet.

## Kafka Event Flow

Every `UserService` mutation publishes a `UserEvent` to topic `user-events` (3 partitions, replicas 1).

**Event envelope** (`UserEvent` record):
```json
{
  "eventId":   "550e8400-e29b-41d4-a716-446655440000",
  "eventType": "USER_CREATED",
  "timestamp": "2026-05-26T09:00:00Z",
  "version":   "1.0",
  "payload": {
    "userId":   1,
    "username": "jsmith",
    "email":    "j@example.com"
  }
}
```

| `eventType`    | Triggered by         |
|----------------|----------------------|
| `USER_CREATED` | `createUser()`       |
| `USER_UPDATED` | `updateUser()`       |
| `USER_DELETED` | `deleteUser()`       |

**Key classes:**

| Class                  | Role                                                  |
|------------------------|-------------------------------------------------------|
| `UserEvent`            | Immutable record; `UserEvent.of(type, payload)` factory sets UUID eventId, `Instant` timestamp, version `"1.0"` |
| `UserEventPayload`     | Immutable record carrying `userId`, `username`, `email` |
| `UserEventType`        | Enum: `USER_CREATED`, `USER_UPDATED`, `USER_DELETED`  |
| `UserEventPublisher`   | Sends to Kafka via `KafkaTemplate`; key = `eventId`   |
| `UserEventConsumer`    | Listens on `user-events`; currently logs each event   |
| `KafkaConfig`          | Declares topic bean; sets concurrency to 3            |

**Serialization:** `JacksonJsonSerializer` / `JacksonJsonDeserializer` (Spring Kafka 4.x, Jackson 3.x compatible). `spring.json.add.type.headers: false` — no type headers written; consumer uses `spring.json.value.default.type` to know the target class.

**Local dev:** `spring.kafka.listener.auto-startup: false` — consumer does not start without a running broker. Kafka is not required to start the app locally.

Kafka bootstrap server: `localhost:9092` (dev). No Kafka is provisioned for QA/prod yet — update `application-qa.yml` / `application-prod.yml` before deploying.

## Package Structure

```
com.enterprise.demo
├── config/          # H2ConsoleConfig, KafkaConfig
├── controller/      # UserController
├── dto/             # UserDto
├── entity/          # User (JPA entity, table: users)
├── event/           # UserEvent, UserEventPayload, UserEventType,
│                    #   UserEventPublisher, UserEventConsumer
├── exception/       # GlobalExceptionHandler, ResourceNotFoundException
├── repository/      # UserRepository (JpaRepository)
├── security/        # Empty — placeholder for future Spring Security
├── service/         # UserService
└── util/            # Empty — placeholder
```

## Known Technical Debt & Constraints

- **No database migrations** — no Flyway or Liquibase; DDL managed by Hibernate (`update` in dev, `validate` in qa/prod requires manual schema setup)
- **JWT dev default** — `application.yml` ships a Base64-encoded development-only default for `JWT_SECRET`. Override this with a strong random value in every non-dev environment via the `JWT_SECRET` environment variable.
- **Kafka not provisioned for QA/prod** — `application-qa.yml` and `application-prod.yml` have no Kafka config; add bootstrap-servers before deploying event-driven features
- **Consumer is log-only** — `UserEventConsumer` logs events but takes no action; downstream processing not yet implemented
- **No API docs** — no Swagger/SpringDoc configured

## CI / CD

| File | Purpose |
|------|---------|
| `.github/workflows/ci.yml` | GitHub Actions pipeline — build, test, JaCoCo gate, SonarCloud |
| `.github/PULL_REQUEST_TEMPLATE.md` | PR checklist (tests, conventions, security, error handling) |
| `.github/CODEOWNERS` | Required reviewers per path |
| `sonar-project.properties` | SonarCloud / SonarQube project settings |

**Branch-protection rules** (set in GitHub → Settings → Branches → master):
- Require status check: `Build · Test · Coverage · Quality`
- Require branches to be up-to-date before merging
- Require at least 1 Code Owner review

**Quality gates** enforced automatically:
- JaCoCo instruction coverage ≥ 80 % (Maven `verify` fails the build)
- SonarCloud quality gate (Sonar way: coverage, duplication, maintainability, security ratings)

## Build & Test

```bash
./mvnw clean package          # build JAR
./mvnw test                   # run tests
./mvnw clean verify           # full build + tests + coverage check
```

## Team & Conventions

- Branch: `master` (default); target `main` for PRs
- Entity fields map directly to DB columns — keep `User` lean, put business logic in `UserService`
- DTOs cross the API boundary; entities stay within the service/repository layer
- Use `@ControllerAdvice` in `GlobalExceptionHandler` for all new exception types — do not add `try/catch` in controllers
