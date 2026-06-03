package com.enterprise.demo;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.KafkaContainer;

/**
 * Extends the Postgres base with a singleton Apache Kafka container.
 * Both containers start once for the JVM lifetime via static initialisers.
 *
 * Tests that need to produce or consume real Kafka messages should extend
 * this class. Tests that only need the database should extend
 * AbstractIntegrationTest directly to avoid pulling in a Kafka container
 * they don't need.
 */
abstract class AbstractKafkaIntegrationTest extends AbstractIntegrationTest {

    static final KafkaContainer KAFKA =
            new KafkaContainer("apache/kafka-native:3.8.0");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }
}
