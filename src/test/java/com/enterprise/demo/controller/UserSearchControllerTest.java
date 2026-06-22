package com.enterprise.demo.controller;

import com.enterprise.demo.client.NotificationClient;
import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.service.AuditService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TDD — RED phase. Six of these seven tests fail because UserController.getAllUsers
 * ignores the ?username / ?email query params and never calls userService.searchUsers.
 *
 * The tests pass once the controller is updated to detect the params and delegate to
 * userService.searchUsers instead of userService.getAllUsers.
 */
@WebMvcTest(UserController.class)
class UserSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private NotificationClient notificationClient;

    // --- username filter ---

    @Test
    void getAllUsers_withUsernameParam_callsSearchUsersNotGetAllUsers() throws Exception {
        // Verifies routing: when ?username is present the controller must call searchUsers.
        // FAILS: controller calls getAllUsers (returns 2 users), not searchUsers (returns 1).
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"),
                        new UserDto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("username", "smith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"));

        verify(userService).searchUsers(eq("smith"), isNull(), any(Pageable.class));
        verify(userService, never()).getAllUsers(any(Pageable.class));
    }

    @Test
    void getAllUsers_withEmailParam_callsSearchUsersWithNullUsername() throws Exception {
        // Verifies routing: when only ?email is present, username must be null in the call.
        // FAILS: controller calls getAllUsers, not searchUsers.
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"),
                        new UserDto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(isNull(), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(userService).searchUsers(isNull(), eq("example.com"), any(Pageable.class));
        verify(userService, never()).getAllUsers(any(Pageable.class));
    }

    @Test
    void getAllUsers_withBothParams_callsSearchUsersWithBothFilters() throws Exception {
        // Verifies routing: when both params are present both must be forwarded.
        // FAILS: controller calls getAllUsers, not searchUsers.
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"),
                        new UserDto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users")
                        .param("username", "smith")
                        .param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(userService).searchUsers(eq("smith"), eq("example.com"), any(Pageable.class));
        verify(userService, never()).getAllUsers(any(Pageable.class));
    }

    // --- response shape ---

    @Test
    void getAllUsers_withUsernameFilter_returnsOnlyMatchingUsersInPage() throws Exception {
        // Verifies the filtered result is what the client receives, not the full list.
        // FAILS: controller ignores ?username and returns the getAllUsers result (2 items).
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"),
                        new UserDto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("username", "smith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"))
                .andExpect(jsonPath("$.content[0].email").value("j@example.com"));
    }

    @Test
    void getAllUsers_withEmailFilter_returnsEmptyPageWhenNothingMatches() throws Exception {
        // Verifies the empty result from searchUsers is passed through correctly.
        // FAILS: controller ignores ?email and returns getAllUsers result instead.
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));
        when(userService.searchUsers(isNull(), eq("zzz.invalid"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("email", "zzz.invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void getAllUsers_withBothFilters_returnsIntersectionResult() throws Exception {
        // Verifies AND semantics: only users matching both filters are returned.
        // FAILS: controller ignores both params and returns all users from getAllUsers.
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"),
                        new UserDto(2L, "bwilson", "b@example.com"))));
        when(userService.searchUsers(eq("wilson"), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(2L, "bwilson", "b@example.com"))));

        mockMvc.perform(get("/api/v1/users")
                        .param("username", "wilson")
                        .param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("bwilson"));
    }

    // --- backward-compatibility ---

    @Test
    void getAllUsers_withNoFilterParams_usesGetAllUsersNotSearchUsers() throws Exception {
        // Existing behaviour must not change: no params → getAllUsers, not searchUsers.
        // PASSES: this is already how the controller works.
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        new UserDto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(userService, never()).searchUsers(any(), any(), any(Pageable.class));
    }
}
