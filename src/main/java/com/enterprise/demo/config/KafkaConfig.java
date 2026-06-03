package com.enterprise.demo.config;

import com.enterprise.demo.event.UserEvent;
import com.enterprise.demo.event.UserEventPublisher;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class KafkaConfig {

    @Bean
    public NewTopic userEventsTopic() {
        return TopicBuilder.name(UserEventPublisher.TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic userEventsDltTopic() {
        return TopicBuilder.name(UserEventPublisher.DLT_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    /**
     * One listener thread per partition (3 partitions → concurrency 3).
     * Retries up to 3 times with exponential backoff (1s → 2s → 4s), then routes to DLT.
     * SerializationException is non-retryable — sent straight to DLT.
     *
     * Scaling constraint: concurrency × running instances must not exceed partition count (3).
     * Kafka assigns at most one consumer per partition; excess threads sit idle.
     * When scaling horizontally, lower concurrency to 1 and run up to 3 instances,
     * or increase partitions proportionally before adding instances.
     */
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, UserEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, UserEvent> consumerFactory,
            KafkaTemplate<String, UserEvent> kafkaTemplate) {

        var recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate);

        var backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxAttempts(3);

        var errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(SerializationException.class);

        var factory = new ConcurrentKafkaListenerContainerFactory<String, UserEvent>();
        factory.setConsumerFactory(consumerFactory);
        factory.setConcurrency(3);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
