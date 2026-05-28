package com.enterprise.demo.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UserEventConsumer {

    @KafkaListener(topics = UserEventPublisher.TOPIC, groupId = "${spring.kafka.consumer.group-id}")
    public void consume(UserEvent event) {
        switch (event.eventType()) {
            case USER_CREATED -> log.info("User created — id={}, username={}",
                    event.payload().userId(), event.payload().username());
            case USER_UPDATED -> log.info("User updated — id={}, username={}",
                    event.payload().userId(), event.payload().username());
            case USER_DELETED -> log.info("User deleted — id={}, username={}",
                    event.payload().userId(), event.payload().username());
        }
    }

    @KafkaListener(topics = UserEventPublisher.DLT_TOPIC, groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void consumeDlt(
            UserEvent event,
            @Header("kafka_dlt-original-topic") String originalTopic,
            @Header("kafka_dlt-exception-message") String exceptionMessage) {
        log.error("DLT message — originalTopic={}, eventId={}, eventType={}, error={}",
                originalTopic, event.eventId(), event.eventType(), exceptionMessage);
    }
}
