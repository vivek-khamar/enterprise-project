package com.enterprise.demo;

import com.enterprise.demo.event.UserEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that UserService mutations publish the correct events to the real
 * Kafka broker started by AbstractKafkaIntegrationTest.
 *
 * UserEventPublisher.publish() calls get(5s) on the send future, so by the
 * time the HTTP response arrives the event is already ACKed by the broker.
 * There is therefore no async polling needed — a single consumer.poll() with
 * a short deadline is sufficient.
 *
 * Shares the same @SpringBootTest properties as UserApiIntegrationTest so
 * Spring Test's context cache reuses the same ApplicationContext.
 */
@SpringBootTest(properties = "spring.profiles.active=integration-test")
@AutoConfigureMockMvc
class UserEventIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Test
    void createUser_publishesUserCreatedEvent() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            prime(consumer);

            mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                    .andExpect(status().isCreated());

            JsonNode event = pollOneEvent(consumer);

            assertThat(event.get("eventType").asText()).isEqualTo("USER_CREATED");
            assertThat(event.get("payload").get("username").asText()).isEqualTo("jsmith");
            assertThat(event.get("payload").get("email").asText()).isEqualTo("j@example.com");
        }
    }

    @Test
    void updateUser_publishesUserUpdatedEvent() throws Exception {
        long id = createUserAndExtractId("alice", "alice@example.com");

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            prime(consumer);

            mockMvc.perform(put(USERS_BASE + "/" + id).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"alice2\",\"email\":\"alice2@example.com\"}"))
                    .andExpect(status().isOk());

            JsonNode event = pollOneEvent(consumer);

            assertThat(event.get("eventType").asText()).isEqualTo("USER_UPDATED");
            assertThat(event.get("payload").get("username").asText()).isEqualTo("alice2");
        }
    }

    @Test
    void deleteUser_publishesUserDeletedEvent() throws Exception {
        long id = createUserAndExtractId("todelete", "del@example.com");

        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            prime(consumer);

            mockMvc.perform(delete(USERS_BASE + "/" + id).with(user("admin").roles("ADMIN")))
                    .andExpect(status().isNoContent());

            JsonNode event = pollOneEvent(consumer);

            assertThat(event.get("eventType").asText()).isEqualTo("USER_DELETED");
            assertThat(event.get("payload").get("userId").asLong()).isEqualTo(id);
        }
    }

    @Test
    void publishedEvent_hasRequiredEnvelopeFields() throws Exception {
        try (KafkaConsumer<String, String> consumer = newConsumer()) {
            prime(consumer);

            mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"evtuser\",\"email\":\"evt@example.com\"}"))
                    .andExpect(status().isCreated());

            JsonNode event = pollOneEvent(consumer);

            assertThat(event.has("eventId")).isTrue();
            assertThat(event.has("eventType")).isTrue();
            assertThat(event.has("timestamp")).isTrue();
            assertThat(event.get("version").asText()).isEqualTo("1.0");
            assertThat(event.has("payload")).isTrue();
            assertThat(event.get("payload").has("userId")).isTrue();
            assertThat(event.get("payload").has("username")).isTrue();
            assertThat(event.get("payload").has("email")).isTrue();
        }
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private KafkaConsumer<String, String> newConsumer() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        return new KafkaConsumer<>(props);
    }

    /**
     * Subscribes the consumer and waits until all partitions are assigned, then
     * explicitly seeks to the end of every partition.
     *
     * A fixed-time poll is unreliable with apache/kafka-native because group
     * coordinator election can take several seconds.  Looping until
     * assignment() is non-empty and then calling seekToEnd() is deterministic:
     * any record produced AFTER this method returns will be visible on the
     * next poll(), regardless of which partition it lands on.
     */
    private void prime(KafkaConsumer<String, String> consumer) {
        consumer.subscribe(List.of(UserEventPublisher.TOPIC));
        // Wait until the group coordinator assigns partitions.
        while (consumer.assignment().isEmpty()) {
            consumer.poll(Duration.ofMillis(500));
        }
        // seekToEnd() is lazy — it only materialises on the NEXT poll/position call.
        // Without the follow-up poll, the seek applies *during* pollOneEvent(), which
        // runs after the event is published, placing the start position past the event.
        // The extra poll here is intentionally empty: it burns off the lazy seek so the
        // position is fixed at "now" before the API call produces anything.
        consumer.seekToEnd(consumer.assignment());
        consumer.poll(Duration.ofMillis(100));
    }

    /**
     * Polls until one record arrives or 10 s elapses.
     * Because publish() blocks until the broker ACKs, the event is already
     * committed before the HTTP response returns, so this normally succeeds on
     * the very first poll.
     */
    private JsonNode pollOneEvent(KafkaConsumer<String, String> consumer) throws Exception {
        Instant deadline = Instant.now().plusSeconds(10);
        while (Instant.now().isBefore(deadline)) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
            if (!records.isEmpty()) {
                return objectMapper.readTree(records.iterator().next().value());
            }
        }
        throw new AssertionError("No Kafka event received within 10 s on topic " + UserEventPublisher.TOPIC);
    }

    private long createUserAndExtractId(String username, String email) throws Exception {
        MvcResult result = mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String body = result.getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }
}
