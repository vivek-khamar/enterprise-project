package com.enterprise.demo.event;

import com.enterprise.demo.exception.EventPublishException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserEventPublisher {

    public static final String TOPIC = "user-events";
    public static final String DLT_TOPIC = TOPIC + ".DLT";

    private final KafkaTemplate<String, UserEvent> kafkaTemplate;

    public void publish(UserEvent event) {
        try {
            kafkaTemplate.send(TOPIC, event.payload().userId().toString(), event).get(5, TimeUnit.SECONDS);
            log.debug("Published {} eventId={}", event.eventType(), event.eventId());
        } catch (Exception ex) {
            log.error("Failed to publish {} eventId={}", event.eventType(), event.eventId(), ex);
            throw new EventPublishException(
                    "Failed to publish " + event.eventType() + " eventId=" + event.eventId(), ex);
        }
    }
}
