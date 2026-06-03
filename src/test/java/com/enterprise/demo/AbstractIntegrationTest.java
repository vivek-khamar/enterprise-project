package com.enterprise.demo;

import com.enterprise.demo.repository.FileMetadataRepository;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Singleton-container base class for all integration tests that need PostgreSQL.
 *
 * The container is started once per JVM run (static initialiser) and shared across
 * every ApplicationContext that uses this class. Ryuk cleans it up on JVM exit.
 *
 * Deliberately carries no Spring test annotations so concrete subclasses remain
 * free to compose whatever @SpringBootTest / @AutoConfigureMockMvc they need.
 */
abstract class AbstractIntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void postgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    protected UserRepository userRepository;

    @Autowired
    protected FileMetadataRepository fileMetadataRepository;

    static final String USERS_BASE = "/api/v1/users";

    @BeforeEach
    void cleanDatabase() {
        fileMetadataRepository.deleteAll();
        userRepository.deleteAll();
    }
}
