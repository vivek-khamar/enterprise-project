package com.enterprise.demo.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    public static final String TOPIC = "user-events";

    private final KafkaTemplate<String, UserEvent> kafkaTemplate;

    public void publish(UserEvent event) {
        kafkaTemplate.send(TOPIC, event.eventId(), event)
                .exceptionally(ex -> {
                    log.error("Failed to publish {} eventId={}", event.eventType(), event.eventId(), ex);
                    return null;
                });
    }
}
