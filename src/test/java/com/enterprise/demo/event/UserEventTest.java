package com.enterprise.demo.event;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class UserEventTest {

    @Test
    void of_setsSchemaVersion() {
        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith", "j@example.com"));

        assertThat(event.version()).isEqualTo("1.0");
    }

    @Test
    void of_setsCorrectEventType() {
        UserEvent event = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "jane", "jane@example.com"));

        assertThat(event.eventType()).isEqualTo(UserEventType.USER_UPDATED);
    }

    @Test
    void of_setsNonNullUuidEventId() {
        UserEvent event = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "bob", "bob@example.com"));

        assertThat(event.eventId()).isNotNull();
        assertThat(event.eventId()).matches(
                "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void of_setsTimestampWithinCallWindow() {
        Instant before = Instant.now();
        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith", "j@example.com"));
        Instant after = Instant.now();

        assertThat(event.timestamp()).isAfterOrEqualTo(before);
        assertThat(event.timestamp()).isBeforeOrEqualTo(after);
    }

    @Test
    void of_mapsPayloadFieldsCorrectly() {
        UserEventPayload payload = new UserEventPayload(42L, "alice", "alice@example.com");

        UserEvent event = UserEvent.of(UserEventType.USER_CREATED, payload);

        assertThat(event.payload().userId()).isEqualTo(42L);
        assertThat(event.payload().username()).isEqualTo("alice");
        assertThat(event.payload().email()).isEqualTo("alice@example.com");
    }

    @Test
    void of_generatesDifferentEventIdEachTime() {
        UserEventPayload payload = new UserEventPayload(1L, "x", "x@x.com");

        UserEvent e1 = UserEvent.of(UserEventType.USER_CREATED, payload);
        UserEvent e2 = UserEvent.of(UserEventType.USER_CREATED, payload);

        assertThat(e1.eventId()).isNotEqualTo(e2.eventId());
    }

    @Test
    void schemaVersionConstant_isOnePointZero() {
        assertThat(UserEvent.SCHEMA_VERSION).isEqualTo("1.0");
    }
}
