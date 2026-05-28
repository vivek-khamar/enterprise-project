package com.enterprise.demo.event;

import com.enterprise.demo.exception.EventPublishException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import org.springframework.kafka.support.SendResult;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, "1", event);
    }

    @Test
    void publish_usesUserIdAsPartitionKey() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UserEvent event = UserEvent.of(UserEventType.USER_UPDATED,
                new UserEventPayload(2L, "jane", "jane@example.com"));

        publisher.publish(event);

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, "2", event);
    }

    @Test
    void publish_sendsEventAsValue() {
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(CompletableFuture.completedFuture(null));
        UserEvent event = UserEvent.of(UserEventType.USER_DELETED,
                new UserEventPayload(3L, "bob", "bob@example.com"));

        publisher.publish(event);

        verify(kafkaTemplate).send(UserEventPublisher.TOPIC, "3", event);
    }

    @Test
    void publish_throwsEventPublishException_whenSendFails() {
        CompletableFuture<SendResult<String, UserEvent>> failed = new CompletableFuture<>();
        failed.completeExceptionally(new RuntimeException("broker unavailable"));
        when(kafkaTemplate.send(any(), any(), any())).thenReturn(failed);

        UserEvent event = UserEvent.of(UserEventType.USER_CREATED,
                new UserEventPayload(1L, "jsmith", "j@example.com"));

        assertThatThrownBy(() -> publisher.publish(event))
                .isInstanceOf(EventPublishException.class)
                .hasMessageContaining(event.eventId())
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    void topicConstant_isUserEvents() {
        assertThat(UserEventPublisher.TOPIC).isEqualTo("user-events");
    }

    @Test
    void dltTopicConstant_isUserEventsDlt() {
        assertThat(UserEventPublisher.DLT_TOPIC).isEqualTo("user-events.DLT");
    }
}
