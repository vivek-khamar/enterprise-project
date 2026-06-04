package com.enterprise.demo;

import com.enterprise.demo.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-stack integration tests for the User API.
 *
 * Extends AbstractKafkaIntegrationTest so that Kafka events produced by
 * UserService are delivered to a real broker — this prevents each mutation
 * from blocking 5 s on a producer timeout.  The tests themselves do not
 * assert on Kafka; that is covered by UserEventIntegrationTest.
 *
 * Both this class and UserEventIntegrationTest share identical @SpringBootTest
 * properties, so Spring Test's context cache serves the same ApplicationContext
 * to both — containers are started only once per JVM run.
 */
@SpringBootTest(properties = "spring.profiles.active=integration-test")
@AutoConfigureMockMvc
class UserApiIntegrationTest extends AbstractKafkaIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    // --- create ---

    @Test
    void createUser_returns201_andUserIsPersisted() throws Exception {
        mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isNotEmpty())
                .andExpect(jsonPath("$.username").value("jsmith"))
                .andExpect(jsonPath("$.email").value("j@example.com"));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(userRepository.findAll().get(0).getUsername()).isEqualTo("jsmith");
    }

    @Test
    void createUser_returns409_whenUsernameAlreadyExists() throws Exception {
        createUser("jsmith", "first@example.com");

        mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"second@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Data conflict"));
    }

    @Test
    void createUser_returns400_forInvalidEmail() throws Exception {
        mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // --- read ---

    @Test
    void getUserById_returns200_withCorrectData() throws Exception {
        MvcResult created = createUserAndReturn("adoe", "adoe@example.com");
        long id = extractId(created);

        mockMvc.perform(get(USERS_BASE + "/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.username").value("adoe"))
                .andExpect(jsonPath("$.email").value("adoe@example.com"));
    }

    @Test
    void getUserById_returns404_whenNotFound() throws Exception {
        mockMvc.perform(get(USERS_BASE + "/9999").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getAllUsers_returns200_withPaginatedResults() throws Exception {
        createUser("alice", "alice@example.com");
        createUser("bob", "bob@example.com");

        mockMvc.perform(get(USERS_BASE).with(user("admin").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    // --- update ---

    @Test
    void updateUser_returns200_andPersistsChangesToPostgres() throws Exception {
        MvcResult created = createUserAndReturn("before", "before@example.com");
        long id = extractId(created);

        mockMvc.perform(put(USERS_BASE + "/" + id).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"after\",\"email\":\"after@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("after"))
                .andExpect(jsonPath("$.email").value("after@example.com"));

        User stored = userRepository.findById(id).orElseThrow();
        assertThat(stored.getUsername()).isEqualTo("after");
        assertThat(stored.getEmail()).isEqualTo("after@example.com");
    }

    @Test
    void updateUser_returns404_whenNotFound() throws Exception {
        mockMvc.perform(put(USERS_BASE + "/9999").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"x\",\"email\":\"x@example.com\"}"))
                .andExpect(status().isNotFound());
    }

    // --- delete ---

    @Test
    void deleteUser_returns204_andRemovesFromPostgres() throws Exception {
        MvcResult created = createUserAndReturn("todelete", "del@example.com");
        long id = extractId(created);

        mockMvc.perform(delete(USERS_BASE + "/" + id).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());

        assertThat(userRepository.findById(id)).isEmpty();
    }

    @Test
    void deleteUser_returns404_whenNotFound() throws Exception {
        mockMvc.perform(delete(USERS_BASE + "/9999").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    // --- search (real Postgres LIKE + LOWER) ---

    @Test
    void searchByUsername_returnsOnlyMatchingUsers() throws Exception {
        createUser("jsmith", "jsmith@example.com");
        createUser("adoe", "adoe@example.com");
        createUser("jjones", "jjones@example.com");

        mockMvc.perform(get(USERS_BASE).with(user("admin").roles("ADMIN")).param("username", "j"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2));
    }

    @Test
    void searchByEmail_returnsOnlyMatchingUsers() throws Exception {
        createUser("alice", "alice@example.com");
        createUser("bob", "bob@company.org");

        mockMvc.perform(get(USERS_BASE).with(user("admin").roles("ADMIN")).param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("alice"));
    }

    @Test
    void search_isCaseInsensitiveInPostgres() throws Exception {
        createUser("JSmith", "JSmith@Example.COM");

        mockMvc.perform(get(USERS_BASE).with(user("admin").roles("ADMIN")).param("username", "jsmith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        mockMvc.perform(get(USERS_BASE).with(user("admin").roles("ADMIN")).param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    // --- helpers ---

    private void createUser(String username, String email) throws Exception {
        mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated());
    }

    private MvcResult createUserAndReturn(String username, String email) throws Exception {
        return mockMvc.perform(post(USERS_BASE).with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"email\":\"" + email + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private long extractId(MvcResult result) throws Exception {
        String body = result.getResponse().getContentAsString();
        String idStr = body.replaceAll(".*\"id\":(\\d+).*", "$1");
        return Long.parseLong(idStr);
    }
}
