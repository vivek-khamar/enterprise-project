package com.enterprise.demo.service;

import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TDD — RED phase. Every test fails with UnsupportedOperationException until
 * UserService.searchUsers is implemented to delegate to
 * UserRepository.searchByFilters and map results to UserDto.
 */
@ExtendWith(MockitoExtension.class)
class UserSearchServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserEventPublisher eventPublisher;

    @InjectMocks
    private UserService userService;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Test
    void searchUsers_delegatesToRepositorySearchByFilters() {
        // Verifies the service forwards the call to the correct repository method.
        // FAILS: stub throws UnsupportedOperationException before reaching the verify.
        User user = userWithId(1L, "jsmith", "j@example.com");
        when(userRepository.searchByFilters(eq("smith"), isNull(), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(user)));

        userService.searchUsers("smith", null, PAGE);

        verify(userRepository).searchByFilters("smith", null, PAGE);
    }

    @Test
    void searchUsers_returnsMappedDtosFromRepositoryPage() {
        // Verifies that User entities from the repository are converted to UserDtos.
        // FAILS: stub throws UnsupportedOperationException.
        User user = userWithId(1L, "jsmith", "j@example.com");
        when(userRepository.searchByFilters(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        Page<UserDto> result = userService.searchUsers("smith", null, PAGE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).getId()).isEqualTo(1L);
        assertThat(result.getContent().get(0).getUsername()).isEqualTo("jsmith");
        assertThat(result.getContent().get(0).getEmail()).isEqualTo("j@example.com");
    }

    @Test
    void searchUsers_withUsernameOnlyPassesNullEmailToRepository() {
        // Verifies that a missing email param is forwarded as null, not as an empty string.
        // FAILS: stub throws UnsupportedOperationException.
        when(userRepository.searchByFilters(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        userService.searchUsers("smith", null, PAGE);

        verify(userRepository).searchByFilters(eq("smith"), isNull(), any(Pageable.class));
    }

    @Test
    void searchUsers_withEmailOnlyPassesNullUsernameToRepository() {
        // Verifies that a missing username param is forwarded as null, not as an empty string.
        // FAILS: stub throws UnsupportedOperationException.
        when(userRepository.searchByFilters(isNull(), eq("example.com"), any(Pageable.class)))
                .thenReturn(Page.empty());

        userService.searchUsers(null, "example.com", PAGE);

        verify(userRepository).searchByFilters(isNull(), eq("example.com"), any(Pageable.class));
    }

    @Test
    void searchUsers_returnsEmptyPageWhenRepositoryFindsNoResults() {
        // Verifies the service propagates an empty page rather than returning null.
        // FAILS: stub throws UnsupportedOperationException.
        when(userRepository.searchByFilters(eq("zzz"), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<UserDto> result = userService.searchUsers("zzz", null, PAGE);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void searchUsers_propagatesPageableToRepository() {
        // Verifies that pagination/sorting settings are forwarded unchanged.
        // FAILS: stub throws UnsupportedOperationException.
        Pageable customPage = PageRequest.of(2, 10);
        when(userRepository.searchByFilters(any(), any(), eq(customPage)))
                .thenReturn(Page.empty());

        userService.searchUsers("doe", null, customPage);

        verify(userRepository).searchByFilters(any(), any(), eq(customPage));
    }

    private User userWithId(Long id, String username, String email) {
        User user = new User(username, email);
        user.setId(id);
        return user;
    }
}
