package com.enterprise.demo.controller;

import com.enterprise.demo.client.NotificationClient;
import com.enterprise.demo.dto.NotificationDto;
import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.service.UserService;
import org.springframework.dao.DataIntegrityViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private NotificationClient notificationClient;

    @Test
    void getAllUsers_returns200WithPagedContent() throws Exception {
        when(userService.getAllUsers(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(
                new UserDto(1L, "jsmith", "j@example.com"),
                new UserDto(2L, "adoe", "a@example.com")
        )));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].username").value("jsmith"))
                .andExpect(jsonPath("$.content[1].username").value("adoe"));
    }

    @Test
    void getAllUsers_returns200WithEmptyPage() throws Exception {
        when(userService.getAllUsers(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isEmpty());
    }

    @Test
    void getUserById_returns200() throws Exception {
        when(userService.getUserById(1L)).thenReturn(new UserDto(1L, "jsmith", "j@example.com"));

        mockMvc.perform(get("/api/v1/users/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jsmith"))
                .andExpect(jsonPath("$.email").value("j@example.com"));
    }

    @Test
    void getUserById_returns404WhenNotFound() throws Exception {
        when(userService.getUserById(99L))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/v1/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"))
                .andExpect(jsonPath("$.details").value("User not found with id: 99"));
    }

    @Test
    void createUser_returns201() throws Exception {
        when(userService.createUser(any(UserDto.class)))
                .thenReturn(new UserDto(1L, "jsmith", "j@example.com"));

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.username").value("jsmith"));
    }

    @Test
    void createUser_returns400ForBlankUsername() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void createUser_returns400ForInvalidEmail() throws Exception {
        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void updateUser_returns200() throws Exception {
        when(userService.updateUser(eq(1L), any(UserDto.class)))
                .thenReturn(new UserDto(1L, "updated", "updated@example.com"));

        mockMvc.perform(put("/api/v1/users/1")
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

        mockMvc.perform(put("/api/v1/users/99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"updated\",\"email\":\"updated@example.com\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void updateUser_returns400ForInvalidBody() throws Exception {
        mockMvc.perform(put("/api/v1/users/1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"bad-email\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void deleteUser_returns204() throws Exception {
        doNothing().when(userService).deleteUser(1L);

        mockMvc.perform(delete("/api/v1/users/1"))
                .andExpect(status().isNoContent());
    }

    @Test
    void deleteUser_returns404WhenNotFound() throws Exception {
        doThrow(new ResourceNotFoundException("User not found with id: 99"))
                .when(userService).deleteUser(99L);

        mockMvc.perform(delete("/api/v1/users/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getUserNotifications_returns200WithList() throws Exception {
        when(userService.getUserById(1L)).thenReturn(new UserDto(1L, "jsmith", "j@example.com"));
        when(notificationClient.getNotificationsForUser(1L)).thenReturn(List.of(
                new NotificationDto(1L, 1L, "jsmith", "Welcome, jsmith!", "USER_CREATED", Instant.now())
        ));

        mockMvc.perform(get("/api/v1/users/1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].userId").value(1))
                .andExpect(jsonPath("$[0].eventType").value("USER_CREATED"));
    }

    @Test
    void getUserNotifications_returns404WhenUserNotFound() throws Exception {
        when(userService.getUserById(99L))
                .thenThrow(new ResourceNotFoundException("User not found with id: 99"));

        mockMvc.perform(get("/api/v1/users/99/notifications"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Resource not found"));
    }

    @Test
    void getUserNotifications_returnsEmptyListWhenServiceUnavailable() throws Exception {
        when(userService.getUserById(1L)).thenReturn(new UserDto(1L, "jsmith", "j@example.com"));
        when(notificationClient.getNotificationsForUser(1L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/users/1/notifications"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void createUser_returns409WhenDataConflict() throws Exception {
        when(userService.createUser(any(UserDto.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));

        mockMvc.perform(post("/api/v1/users")
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

        mockMvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("Internal Server Error"))
                .andExpect(jsonPath("$.details").value("An unexpected error occurred"));
    }
}
