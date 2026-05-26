package com.enterprise.demo.event;

import java.time.Instant;
import java.util.UUID;

/**
 * Envelope published to the "user-events" Kafka topic for every user mutation.
 *
 * JSON shape:
 * {
 *   "eventId":   "550e8400-e29b-41d4-a716-446655440000",
 *   "eventType": "USER_CREATED",
 *   "timestamp": "2026-05-26T09:00:00Z",
 *   "version":   "1.0",
 *   "payload": {
 *     "userId":   1,
 *     "username": "jsmith",
 *     "email":    "j@example.com"
 *   }
 * }
 */
public record UserEvent(
        String eventId,
        UserEventType eventType,
        Instant timestamp,
        String version,
        UserEventPayload payload
) {
    public static final String SCHEMA_VERSION = "1.0";

    public static UserEvent of(UserEventType eventType, UserEventPayload payload) {
        return new UserEvent(
                UUID.randomUUID().toString(),
                eventType,
                Instant.now(),
                SCHEMA_VERSION,
                payload
        );
    }
}
