package com.enterprise.demo.controller;

import com.enterprise.demo.client.NotificationClient;
import com.enterprise.demo.dto.AdminUserDto;
import com.enterprise.demo.dto.NotificationDto;
import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.exception.SelfModificationException;
import com.enterprise.demo.security.Role;
import com.enterprise.demo.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import com.enterprise.demo.security.SecurityConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.service.AuditService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import org.springframework.security.test.context.support.WithMockUser;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
@Import(SecurityConfig.class)
class UserControllerTest {

    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;

    @MockitoBean private JwtUtil jwtUtil;
    @MockitoBean private UserDetailsService userDetailsService;
    @MockitoBean private AuditService auditService;
    @MockitoBean private UserService userService;
    @MockitoBean private NotificationClient notificationClient;

    private static final Instant NOW = Instant.parse("2026-06-22T10:00:00Z");

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
    }

    private static AdminUserDto adminDto(Long id, String username, String email) {
        return new AdminUserDto(id, username, email, Role.USER, true, NOW);
    }

    // ── GET / — list ──────────────────────────────────────────────────────────

    // ── read endpoints — any authenticated user ───────────────────────────────

    @Test
    void getAllUsers_returns200WithPagedContent() throws Exception {
        when(userService.getAllUsers(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                adminDto(1L, "jsmith", "j@example.com"),
                adminDto(2L, "adoe", "a@example.com")
        )));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"))
                .andExpect(jsonPath("$.content[0].role").value("USER"))
                .andExpect(jsonPath("$.content[0].enabled").value(true))
                .andExpect(jsonPath("$.content[1].username").value("adoe"));
    }

    @Test
    void getAllUsers_returns200WithEmptyPage() throws Exception {
        when(userService.getAllUsers(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/users").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    // ── GET /{id} ─────────────────────────────────────────────────────────────

    @Test
    void getUserById_returns200() throws Exception {
        when(userService.getUserById(1L)).thenReturn(adminDto(1L, "jsmith", "j@example.com"));

        mockMvc.perform(get("/api/v1/users/1").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jsmith"))
                .andExpect(jsonPath("$.email").value("j@example.com"))
                .andExpect(jsonPath("$.role").value("USER"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void getUserById_returns404WhenNotFound() throws Exception {
        when(userService.getUserById(99L))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/v1/users/99").with(user("jsmith").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.details").value("User not found with id: 99"));
    }

    // ── POST / — create ───────────────────────────────────────────────────────

    @Test
    void createUser_returns201() throws Exception {
        when(userService.createUser(any(UserDto.class)))
                .thenReturn(new UserDto(1L, "jsmith", "j@example.com"));

        mockMvc.perform(post("/api/v1/users").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jsmith"));
    }

    @Test
    void createUser_returns400ForBlankUsername() throws Exception {
        mockMvc.perform(post("/api/v1/users").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void createUser_returns400ForInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/users").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void createUser_returns409WhenDataConflict() throws Exception {
        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        mockMvc.perform(post("/api/v1/users").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Data conflict"))
                .andExpect(jsonPath("$.details").value("A resource with the given data already exists"));
    }

    @Test
    void createUser_returns500ForUnhandledException() throws Exception {
        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new RuntimeException("unexpected failure"));

        mockMvc.perform(post("/api/v1/users").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal Server Error"))
                .andExpect(jsonPath("$.details").value("An unexpected error occurred"));
    }

    // ── PUT /{id} — update ────────────────────────────────────────────────────

    @Test
    void updateUser_returns200() throws Exception {
        when(userService.updateUser(eq(1L), any(UserDto.class)))
                .thenReturn(new UserDto(1L, "updated", "updated@example.com"));

        mockMvc.perform(put("/api/v1/users/1").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"updated\",\"email\":\"updated@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("updated"))
                .andExpect(jsonPath("$.email").value("updated@example.com"));
    }

    @Test
    void updateUser_returns404WhenNotFound() throws Exception {
        when(userService.updateUser(eq(99L), any(UserDto.class)))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(put("/api/v1/users/99").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"updated\",\"email\":\"updated@example.com\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void updateUser_returns400ForInvalidBody() throws Exception {
        mockMvc.perform(put("/api/v1/users/1").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"bad-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // ── DELETE /{id} ──────────────────────────────────────────────────────────

    @Test
    void deleteUser_returns204() throws Exception {
        doNothing().when(userService).deleteUser(1L);

        mockMvc.perform(delete("/api/v1/users/1").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_returns404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("User not found with id: 99"))
                .when(userService).deleteUser(99L);

        mockMvc.perform(delete("/api/v1/users/99").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    // ── PATCH /{id}/status ────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateStatus_adminDisablesUser_returns200() throws Exception {
        AdminUserDto disabled = new AdminUserDto(1L, "jsmith", "j@example.com", Role.USER, false, NOW);
        when(userService.enableDisableUser(eq(1L), eq(false), eq("admin"))).thenReturn(disabled);

        mockMvc.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateStatus_adminEnablesUser_returns200() throws Exception {
        when(userService.enableDisableUser(eq(1L), eq(true), eq("admin")))
                .thenReturn(adminDto(1L, "jsmith", "j@example.com"));

        mockMvc.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    @WithMockUser(username = "jsmith", roles = "USER")
    void updateStatus_nonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateStatus_selfDisable_returns400() throws Exception {
        when(userService.enableDisableUser(eq(1L), anyBoolean(), eq("admin")))
                .thenThrow(new SelfModificationException());

        mockMvc.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot modify your own account status or role"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateStatus_notFound_returns404() throws Exception {
        when(userService.enableDisableUser(eq(99L), anyBoolean(), eq("admin")))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(patch("/api/v1/users/99/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateStatus_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // ── PATCH /{id}/role ──────────────────────────────────────────────────────

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_adminPromotes_returns200() throws Exception {
        AdminUserDto promoted = new AdminUserDto(1L, "jsmith", "j@example.com", Role.ADMIN, true, NOW);
        when(userService.changeRole(eq(1L), eq(Role.ADMIN), eq("admin"))).thenReturn(promoted);

        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_adminDemotes_returns200() throws Exception {
        AdminUserDto demoted = new AdminUserDto(1L, "jsmith", "j@example.com", Role.USER, true, NOW);
        when(userService.changeRole(eq(1L), eq(Role.USER), eq("admin"))).thenReturn(demoted);

        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"));
    }

    @Test
    @WithMockUser(username = "jsmith", roles = "USER")
    void updateRole_nonAdmin_returns403() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_selfRoleChange_returns400() throws Exception {
        when(userService.changeRole(eq(1L), any(Role.class), eq("admin")))
                .thenThrow(new SelfModificationException());

        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Cannot modify your own account status or role"));
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_notFound_returns404() throws Exception {
        when(userService.changeRole(eq(99L), any(Role.class), eq("admin")))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(patch("/api/v1/users/99/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"USER\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_invalidRole_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"SUPERUSER\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = "admin", roles = "ADMIN")
    void updateRole_missingBody_returns400() throws Exception {
        mockMvc.perform(patch("/api/v1/users/1/role")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    @Test
    void getUserNotifications_returns200WithList() throws Exception {
        when(userService.getUserById(1L)).thenReturn(adminDto(1L, "jsmith", "j@example.com"));
        when(notificationClient.getNotificationsForUser(1L)).thenReturn(List.of(
                new NotificationDto(1L, 1L, "jsmith", "Welcome, jsmith!", "USER_CREATED", NOW)
        ));

        mockMvc.perform(get("/api/v1/users/1/notifications").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].eventType").value("USER_CREATED"));
    }

    @Test
    void getUserNotifications_returns404WhenUserNotFound() throws Exception {
        when(userService.getUserById(99L))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/v1/users/99/notifications").with(user("jsmith").roles("USER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getUserNotifications_returnsEmptyListWhenServiceUnavailable() throws Exception {
        when(userService.getUserById(1L)).thenReturn(adminDto(1L, "jsmith", "j@example.com"));
        when(notificationClient.getNotificationsForUser(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users/1/notifications").with(user("jsmith").roles("USER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
