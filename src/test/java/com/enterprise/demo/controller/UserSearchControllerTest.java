package com.enterprise.demo.controller;

import com.enterprise.demo.client.NotificationClient;
import com.enterprise.demo.dto.AdminUserDto;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.security.Role;
import com.enterprise.demo.service.AuditService;
import com.enterprise.demo.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

@WebMvcTest(UserController.class)
class UserSearchControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private UserService userService;
    @MockitoBean private NotificationClient notificationClient;

    private static AdminUserDto dto(Long id, String username, String email) {
        return new AdminUserDto(id, username, email, Role.USER, true, null);
    }

    // --- username filter ---

    @Test
    void getAllUsers_withUsernameParam_callsSearchUsersNotGetAllUsers() throws Exception {
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(1L, "jsmith", "j@example.com"), dto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("username", "smith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"));

        verify(userService).searchUsers(eq("smith"), isNull(), any(Pageable.class));
        verify(userService, never()).getAllUsers(any(Pageable.class));
    }

    @Test
    void getAllUsers_withEmailParam_callsSearchUsersWithNullUsername() throws Exception {
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(1L, "jsmith", "j@example.com"), dto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(isNull(), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("email", "example.com"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(userService).searchUsers(isNull(), eq("example.com"), any(Pageable.class));
        verify(userService, never()).getAllUsers(any(Pageable.class));
    }

    @Test
    void getAllUsers_withBothParams_callsSearchUsersWithBothFilters() throws Exception {
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(1L, "jsmith", "j@example.com"), dto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));

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
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(1L, "jsmith", "j@example.com"), dto(2L, "adoe", "a@example.com"))));
        when(userService.searchUsers(eq("smith"), isNull(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("username", "smith"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"))
                .andExpect(jsonPath("$.content[0].email").value("j@example.com"));
    }

    @Test
    void getAllUsers_withEmailFilter_returnsEmptyPageWhenNothingMatches() throws Exception {
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));
        when(userService.searchUsers(isNull(), eq("zzz.invalid"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")).param("email", "zzz.invalid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void getAllUsers_withBothFilters_returnsIntersectionResult() throws Exception {
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(
                        dto(1L, "jsmith", "j@example.com"), dto(2L, "bwilson", "b@example.com"))));
        when(userService.searchUsers(eq("wilson"), eq("example.com"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(2L, "bwilson", "b@example.com"))));

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
        when(userService.getAllUsers(any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(dto(1L, "jsmith", "j@example.com"))));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1));

        verify(userService, never()).searchUsers(any(), any(), any(Pageable.class));
    }
}
