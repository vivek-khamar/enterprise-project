package com.enterprise.demo.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatNoException;

@ExtendWith(MockitoExtension.class)
class UserEventConsumerTest {

    private final UserEventConsumer consumer = new UserEventConsumer();

    @Test
    void consume_userCreatedEvent_doesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }

    @Test
    void consume_userUpdatedEvent_doesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "jane"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }

    @Test
    void consume_userDeletedEvent_doesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "bob"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }

    @Test
    void consumeDlt_logsAndDoesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith"));

        assertThatNoException().isThrownBy(() ->
                consumer.consumeDlt(event, "user-events", "processing failed"));
    }

    @Test
    void consumeDlt_handlesAllEventTypes() {
        UserEvent created = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "a"));
        UserEvent updated = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "b"));
        UserEvent deleted = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "c"));

        assertThatNoException().isThrownBy(() -> {
            consumer.consumeDlt(created, "user-events", "error 1");
            consumer.consumeDlt(updated, "user-events", "error 2");
            consumer.consumeDlt(deleted, "user-events", "error 3");
        });
    }
}
