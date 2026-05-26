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
                new UserEventPayload(1L, "jsmith", "j@example.com"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }

    @Test
    void consume_userUpdatedEvent_doesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "jane", "jane@example.com"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }

    @Test
    void consume_userDeletedEvent_doesNotThrow() {
        UserEvent event = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "bob", "bob@example.com"));

        assertThatNoException().isThrownBy(() -> consumer.consume(event));
    }
}
