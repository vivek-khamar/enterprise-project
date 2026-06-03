package com.enterprise.demo;

import com.enterprise.demo.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies that user mutations succeed (2xx) even when the Kafka broker is
 * completely unreachable.
 *
 * The broker is pointed at localhost:19999 where nothing listens.
 * max.block.ms=1000 caps the producer's metadata-fetch wait to 1 s so each
 * test completes quickly.  The graceful-failure fix in UserService swallows
 * the resulting EventPublishException after the DB commit, so the HTTP
 * response is always 2xx.
 *
 * Only Postgres (from AbstractIntegrationTest) is running here; there is
 * intentionally no Kafka container.  Spring's context cache gives this class
 * its own ApplicationContext because the kafka properties differ from the
 * Kafka-enabled tests.
 */
@SpringBootTest(properties = {
        "spring.profiles.active=integration-test",
        "spring.kafka.bootstrap-servers=localhost:19999",
        "spring.kafka.producer.properties.max.block.ms=1000",
        "spring.kafka.admin.fail-fast=false"
})
@AutoConfigureMockMvc
class KafkaDownIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createUser_returns201_whenKafkaIsUnavailable() throws Exception {
        mockMvc.perform(post(USERS_BASE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.username").value("jsmith"));

        assertThat(userRepository.count()).isEqualTo(1);
    }

    @Test
    void updateUser_returns200_whenKafkaIsUnavailable() throws Exception {
        User saved = userRepository.save(new User("before", "before@example.com"));

        mockMvc.perform(put(USERS_BASE + "/" + saved.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"after\",\"email\":\"after@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("after"));

        assertThat(userRepository.findById(saved.getId()).orElseThrow().getUsername())
                .isEqualTo("after");
    }

    @Test
    void deleteUser_returns204_whenKafkaIsUnavailable() throws Exception {
        User saved = userRepository.save(new User("todelete", "del@example.com"));

        mockMvc.perform(delete(USERS_BASE + "/" + saved.getId()))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(saved.getId())).isEmpty();
    }
}
