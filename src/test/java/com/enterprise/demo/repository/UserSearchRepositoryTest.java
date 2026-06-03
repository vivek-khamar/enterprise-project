package com.enterprise.demo.repository;

import com.enterprise.demo.entity.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TDD — RED phase. All tests except searchByFilters_returnsEmptyPageWhenNothingMatches
 * fail until the WHERE 1=0 placeholder in UserRepository.searchByFilters is replaced
 * with a real LIKE filter query.
 */
@DataJpaTest
class UserSearchRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void setUp() {
        userRepository.saveAll(java.util.List.of(
                new User("jsmith", "jsmith@example.com"),
                new User("adoe", "adoe@company.org"),
                new User("bwilson", "bwilson@example.com"),
                new User("JOHNSON", "johnson@test.net")
        ));
    }

    // --- username filter ---

    @Test
    void searchByFilters_returnsMatchingUserByPartialUsername() {
        // "smith" is a substring of "jsmith" — expects exactly one result.
        // FAILS: placeholder returns empty page (WHERE 1=0).
        Page<User> result = userRepository.searchByFilters("smith", null,
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(User::getUsername)
                .containsExactly("jsmith");
    }

    @Test
    void searchByFilters_isCaseInsensitiveForUsername() {
        // "SMITH" must match "jsmith" regardless of case.
        // FAILS: placeholder returns empty page (WHERE 1=0).
        Page<User> result = userRepository.searchByFilters("SMITH", null,
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(User::getUsername)
                .containsExactly("jsmith");
    }

    // --- email filter ---

    @Test
    void searchByFilters_returnsMultipleUsersForPartialEmailDomain() {
        // "example.com" appears in jsmith@example.com and bwilson@example.com.
        // FAILS: placeholder returns empty page (WHERE 1=0).
        Page<User> result = userRepository.searchByFilters(null, "example.com",
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent())
                .hasSize(2)
                .extracting(User::getUsername)
                .containsExactlyInAnyOrder("jsmith", "bwilson");
    }

    @Test
    void searchByFilters_isCaseInsensitiveForEmail() {
        // "EXAMPLE.COM" must match the two lower-case example.com addresses.
        // FAILS: placeholder returns empty page (WHERE 1=0).
        Page<User> result = userRepository.searchByFilters(null, "EXAMPLE.COM",
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent()).hasSize(2);
    }

    // --- both filters (AND logic) ---

    @Test
    void searchByFilters_appliesBothFiltersWithAndLogic() {
        // "wilson" matches bwilson; "example" matches jsmith and bwilson.
        // AND of both leaves only bwilson.
        // FAILS: placeholder returns empty page (WHERE 1=0).
        Page<User> result = userRepository.searchByFilters("wilson", "example",
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent())
                .hasSize(1)
                .extracting(User::getUsername)
                .containsExactly("bwilson");
    }

    @Test
    void searchByFilters_returnsNoResultsWhenAndFiltersDoNotOverlap() {
        // "doe" matches adoe; "example.com" does NOT contain adoe's email (adoe@company.org).
        // AND gives zero results.
        // FAILS: placeholder returns empty page (WHERE 1=0) — passes here by coincidence,
        // but must remain passing after real implementation too.
        Page<User> result = userRepository.searchByFilters("doe", "example.com",
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getContent()).isEmpty();
    }

    // --- null / blank params treated as "no filter" ---

    @Test
    void searchByFilters_returnsAllUsersWhenBothParamsAreNull() {
        // Null params mean no restriction: every saved user should come back.
        // FAILS: placeholder always returns empty (WHERE 1=0), but real impl returns all 4.
        Page<User> result = userRepository.searchByFilters(null, null,
                PageRequest.of(0, 10, Sort.by("id")));

        assertThat(result.getTotalElements()).isEqualTo(4);
    }

    // --- pagination ---

    @Test
    void searchByFilters_respectsPageSizeWhileFilterIsApplied() {
        // "example.com" matches 2 users; requesting page size 1 should return
        // totalElements=2 but content of size 1.
        // FAILS: placeholder returns empty page (WHERE 1=0), so totalElements=0.
        Page<User> result = userRepository.searchByFilters(null, "example.com",
                PageRequest.of(0, 1, Sort.by("id")));

        assertThat(result.getTotalElements()).isEqualTo(2);
        assertThat(result.getContent()).hasSize(1);
    }
}
