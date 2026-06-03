## What and why

<!-- Describe the problem this PR solves, or the feature it adds.
     Focus on WHY — the code diff already shows WHAT changed.
     Link any related issue: "Closes #123" or "Relates to #456". -->



## Type of change

- [ ] Bug fix (non-breaking change that fixes an issue)
- [ ] New feature (non-breaking change that adds functionality)
- [ ] Breaking change (fix or feature that alters existing API or behaviour)
- [ ] Refactor / cleanup (no functional change)
- [ ] Performance improvement
- [ ] Dependency bump
- [ ] CI / infrastructure change

## Environment and config changes

<!-- List any new env vars, property keys, DB schema changes, or
     infrastructure prerequisites needed before this can be deployed. -->

| What | Before | After |
|------|--------|-------|
| —    | —      | —     |

---

## Review checklist

> The author completes this before requesting review.
> Reviewers verify each item before approving.

### Testing
- [ ] Unit tests added / updated for all new logic (happy path **and** error paths)
- [ ] Integration tests added where the change touches the database, Kafka, or S3
- [ ] CI coverage gate passes (≥ 80 % instruction coverage — enforced by JaCoCo in the pipeline)
- [ ] Tests follow existing patterns:
      Mockito + `@ExtendWith(MockitoExtension.class)` for units,
      TestContainers for integration tests

### Code conventions
- [ ] No `try/catch` in controllers — all exceptions delegated to `GlobalExceptionHandler`
- [ ] New exception types extend an appropriate base class **and** have an `@ExceptionHandler` in `GlobalExceptionHandler`
- [ ] DTOs are used at the API boundary; JPA entities do not escape the service/repository layer
- [ ] `@Transactional` applied on service mutations, not on controllers or repositories
- [ ] Lombok annotations used in place of boilerplate
      (`@RequiredArgsConstructor` for injection, `@Getter`/`@Setter` on entities, etc.)
- [ ] No hardcoded URLs, ports, credentials, or environment-specific values in source code

### Security
- [ ] No secrets, tokens, or passwords committed to the repository
- [ ] User-supplied input validated with Jakarta Bean Validation constraints (`@NotBlank`, `@Email`, etc.)
- [ ] Sensitive data (PII, tokens) is not written to logs
- [ ] Any new endpoint is explicitly handled in `SecurityConfig`
      (either protected or `permitAll()` with a documented reason for the exception)
- [ ] File uploads reject unexpected MIME types via `InvalidFileException`

### Error handling
- [ ] All error responses use the standard `{ timestamp, message, details }` envelope
- [ ] HTTP status codes are semantically correct
      (201 Created, 204 No Content, 400 Bad Request, 404 Not Found, 409 Conflict, 500 Server Error)
- [ ] Kafka publish failures are swallowed in `afterCommit()` and do not poison the HTTP response
- [ ] External service failures (S3, notification service) degrade gracefully with a logged warning

### PR description
- [ ] The "What and why" section explains the motivation, not just the mechanics
- [ ] Breaking changes to the API contract are explicitly called out
- [ ] New config/env requirements are listed in the table above
- [ ] Related issues are linked
