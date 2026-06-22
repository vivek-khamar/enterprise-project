package com.enterprise.demo.event;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.boot.test.json.JacksonTester;

import java.io.IOException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@JsonTest
class UserEventSerializationTest {

    @Autowired
    private JacksonTester<UserEvent> json;

    @Test
    void serialize_topLevelFieldsPresent() throws IOException {
        UserEvent event = new UserEvent(
                "550e8400-e29b-41d4-a716-446655440000",
                UserEventType.USER_CREATED,
                Instant.parse("2026-05-26T09:00:00Z"),
                "1.0",
                new UserEventPayload(1L, "jsmith")
        );

        assertThat(json.write(event)).extractingJsonPathStringValue("$.eventId")
                .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(json.write(event)).extractingJsonPathStringValue("$.eventType")
                .isEqualTo("USER_CREATED");
        assertThat(json.write(event)).extractingJsonPathStringValue("$.version")
                .isEqualTo("1.0");
        assertThat(json.write(event)).hasJsonPathValue("$.timestamp");
    }

    @Test
    void serialize_eventTypeWrittenAsStringNotOrdinal() throws IOException {
        UserEvent event = new UserEvent("id", UserEventType.USER_UPDATED,
                Instant.now(), "1.0", new UserEventPayload(1L, "u"));

        assertThat(json.write(event)).extractingJsonPathStringValue("$.eventType")
                .isEqualTo("USER_UPDATED");
        assertThat(json.write(event)).doesNotHaveJsonPathValue("$.eventType[?(@==1)]");
    }

    @Test
    void serialize_payloadNestedCorrectly() throws IOException {
        UserEvent event = new UserEvent("id", UserEventType.USER_DELETED,
                Instant.now(), "1.0", new UserEventPayload(42L, "alice"));

        assertThat(json.write(event)).extractingJsonPathNumberValue("$.payload.userId")
                .isEqualTo(42);
        assertThat(json.write(event)).extractingJsonPathStringValue("$.payload.username")
                .isEqualTo("alice");
    }

    @Test
    void deserialize_parsesAllFields() throws IOException {
        String content = """
                {
                  "eventId": "550e8400-e29b-41d4-a716-446655440000",
                  "eventType": "USER_DELETED",
                  "timestamp": "2026-05-26T09:00:00Z",
                  "version": "1.0",
                  "payload": {
                    "userId": 5,
                    "username": "bob"
                  }
                }
                """;

        UserEvent event = json.parse(content).getObject();

        assertThat(event.eventId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        assertThat(event.eventType()).isEqualTo(UserEventType.USER_DELETED);
        assertThat(event.version()).isEqualTo("1.0");
        assertThat(event.payload().userId()).isEqualTo(5L);
        assertThat(event.payload().username()).isEqualTo("bob");
    }

    @Test
    void deserialize_timestampRoundTrips() throws IOException {
        String content = """
                {
                  "eventId": "abc",
                  "eventType": "USER_CREATED",
                  "timestamp": "2026-05-26T09:00:00Z",
                  "version": "1.0",
                  "payload": {"userId": 1, "username": "u"}
                }
                """;

        UserEvent event = json.parse(content).getObject();

        assertThat(event.timestamp()).isEqualTo(Instant.parse("2026-05-26T09:00:00Z"));
    }

    @Test
    void deserialize_allEventTypesRecognized() throws IOException {
        for (UserEventType type : UserEventType.values()) {
            String content = """
                    {
                      "eventId": "id",
                      "eventType": "%s",
                      "timestamp": "2026-05-26T09:00:00Z",
                      "version": "1.0",
                      "payload": {"userId": 1, "username": "u"}
                    }
                    """.formatted(type.name());

            UserEvent event = json.parse(content).getObject();
            assertThat(event.eventType()).isEqualTo(type);
        }
    }
}
