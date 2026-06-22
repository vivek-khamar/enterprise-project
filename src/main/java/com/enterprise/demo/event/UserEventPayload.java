package com.enterprise.demo.event;

/**
 * The data carried inside every user domain event.
 *
 * Email was intentionally removed (data-minimisation principle).
 * Downstream consumers that need the user's email must query the
 * User API by userId rather than reading it from the event.
 * This prevents PII propagating into the Kafka topic, broker storage,
 * and any consumer that may not require encryption at rest.
 */
public record UserEventPayload(Long userId, String username) {
}
