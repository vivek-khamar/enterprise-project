package com.enterprise.demo.event;

import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
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
}
