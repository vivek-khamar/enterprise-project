package com.enterprise.demo.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserEventPublisherTest {

    @Mock
    private KafkaTemplate<String, UserEvent> kafkaTemplate;

    @InjectMocks
    private UserEventPublisher publisher;

    @Test
    void publish_sendsToUserEventsTopic() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith", "j@example.com"));

        publisher.publish(event);

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, event.eventId(), event);
    }

    @Test
    void publish_usesEventIdAsMessageKey() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UserEvent event = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "jane", "jane@example.com"));

        publisher.publish(event);

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, event.eventId(), event);
    }

    @Test
    void publish_sendsEventAsValue() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UserEvent event = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "bob", "bob@example.com"));

        publisher.publish(event);

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, event.eventId(), event);
    }

    @Test
    void topicConstant_isUserEvents() {
        assertThat(UserEventPublisher.TOPIC).isEqualTo("user-events");
    }
}
