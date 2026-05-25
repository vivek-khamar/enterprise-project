package com.enterprise.demo.repository;

import com.enterprise.demo.entity.User;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class UserRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Test
    void save_persistsUser() {
        User saved = userRepository.save(new User("jsmith", "j@example.com"));

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getUsername()).isEqualTo("jsmith");
        assertThat(saved.getEmail()).isEqualTo("j@example.com");
    }

    @Test
    void findById_returnsUser() {
        User saved = userRepository.save(new User("jsmith", "j@example.com"));

        Optional<User> found = userRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getUsername()).isEqualTo("jsmith");
        assertThat(found.get().getEmail()).isEqualTo("j@example.com");
    }

    @Test
    void findById_returnsEmptyWhenNotFound() {
        Optional<User> found = userRepository.findById(999L);

        assertThat(found).isEmpty();
    }

    @Test
    void findAll_returnsAllUsers() {
        userRepository.save(new User("user1", "user1@example.com"));
        userRepository.save(new User("user2", "user2@example.com"));

        List<User> users = userRepository.findAll();

        assertThat(users).hasSize(2);
    }

    @Test
    void delete_removesUser() {
        User saved = userRepository.save(new User("jsmith", "j@example.com"));
        userRepository.delete(saved);

        assertThat(userRepository.findById(saved.getId())).isEmpty();
    }

    @Test
    void save_updatesExistingUser() {
        User saved = userRepository.save(new User("old", "old@example.com"));
        saved.setUsername("new");
        saved.setEmail("new@example.com");
        User updated = userRepository.save(saved);

        assertThat(updated.getUsername()).isEqualTo("new");
        assertThat(updated.getEmail()).isEqualTo("new@example.com");
        assertThat(userRepository.count()).isEqualTo(1);
    }
}
