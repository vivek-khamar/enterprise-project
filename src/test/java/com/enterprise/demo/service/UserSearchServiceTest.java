package com.enterprise.demo.service;

import com.enterprise.demo.dto.AdminUserDto;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.event.UserEventPublisher;
import com.enterprise.demo.repository.RefreshTokenRepository;
import com.enterprise.demo.repository.UserRepository;
import com.enterprise.demo.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserSearchServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private UserEventPublisher eventPublisher;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private AuditService auditService;

    @InjectMocks
    private UserService userService;

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Test
    void searchUsers_delegatesToRepositorySearchByFilters() {
        User user = userWithId(1L, "jsmith", "j@example.com");
        when(userRepository.searchByFilters(eq("smith"), isNull(), eq(PAGE)))
                .thenReturn(new PageImpl<>(List.of(user)));

        userService.searchUsers("smith", null, PAGE);

        verify(userRepository).searchByFilters("smith", null, PAGE);
    }

    @Test
    void searchUsers_returnsMappedAdminDtosFromRepositoryPage() {
        User user = userWithId(1L, "jsmith", "j@example.com");
        when(userRepository.searchByFilters(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(user)));

        Page<AdminUserDto> result = userService.searchUsers("smith", null, PAGE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().get(0).id()).isEqualTo(1L);
        assertThat(result.getContent().get(0).username()).isEqualTo("jsmith");
        assertThat(result.getContent().get(0).email()).isEqualTo("j@example.com");
        assertThat(result.getContent().get(0).role()).isEqualTo(Role.USER);
        assertThat(result.getContent().get(0).enabled()).isTrue();
    }

    @Test
    void searchUsers_withUsernameOnlyPassesNullEmailToRepository() {
        when(userRepository.searchByFilters(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        userService.searchUsers("smith", null, PAGE);

        verify(userRepository).searchByFilters(eq("smith"), isNull(), any(Pageable.class));
    }

    @Test
    void searchUsers_withEmailOnlyPassesNullUsernameToRepository() {
        when(userRepository.searchByFilters(isNull(), eq("example.com"), any(Pageable.class)))
                .thenReturn(Page.empty());

        userService.searchUsers(null, "example.com", PAGE);

        verify(userRepository).searchByFilters(isNull(), eq("example.com"), any(Pageable.class));
    }

    @Test
    void searchUsers_returnsEmptyPageWhenRepositoryFindsNoResults() {
        when(userRepository.searchByFilters(eq("zzz"), isNull(), any(Pageable.class)))
                .thenReturn(Page.empty());

        Page<AdminUserDto> result = userService.searchUsers("zzz", null, PAGE);

        assertThat(result).isNotNull();
        assertThat(result.getContent()).isEmpty();
        assertThat(result.getTotalElements()).isZero();
    }

    @Test
    void searchUsers_propagatesPageableToRepository() {
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
