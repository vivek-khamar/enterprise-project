package com.enterprise.demo.controller;

import com.enterprise.demo.client.NotificationClient;
import com.enterprise.demo.dto.AdminUserDto;
import com.enterprise.demo.dto.NotificationDto;
import com.enterprise.demo.dto.UpdateRoleRequest;
import com.enterprise.demo.dto.UpdateStatusRequest;
import com.enterprise.demo.dto.UserDto;
import com.enterprise.demo.service.UserService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
@Validated   // enables method-level constraint validation (e.g. @Positive on path vars)
public class UserController {

    private final UserService userService;
    private final NotificationClient notificationClient;

    @GetMapping
    public ResponseEntity<Page<AdminUserDto>> getAllUsers(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String email,
            @PageableDefault(size = 50, sort = "id") Pageable pageable) {
        String usernameFilter = (username != null && !username.isBlank()) ? username : null;
        String emailFilter = (email != null && !email.isBlank()) ? email : null;
        if (usernameFilter != null || emailFilter != null) {
            return ResponseEntity.ok(userService.searchUsers(usernameFilter, emailFilter, pageable));
        }
        return ResponseEntity.ok(userService.getAllUsers(pageable));
    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserDto> getUserById(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id) {
        return ResponseEntity.ok(userService.getUserById(id));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<AdminUserDto> updateUserStatus(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id,
            @Valid @RequestBody UpdateStatusRequest request) {
        String adminUsername = currentAdminUsername();
        return ResponseEntity.ok(userService.enableDisableUser(id, request.getEnabled(), adminUsername));
    }

    @PatchMapping("/{id}/role")
    public ResponseEntity<AdminUserDto> updateUserRole(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id,
            @Valid @RequestBody UpdateRoleRequest request) {
        String adminUsername = currentAdminUsername();
        return ResponseEntity.ok(userService.changeRole(id, request.getRole(), adminUsername));
    }

    private String currentAdminUsername() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null) {
            throw new IllegalStateException("No authenticated principal found for this request");
        }
        return authentication.getName();
    }

    @PostMapping
    public ResponseEntity<UserDto> createUser(@Valid @RequestBody UserDto userDto) {
        UserDto createdUser = userService.createUser(userDto);
        return new ResponseEntity<>(createdUser, HttpStatus.CREATED);
    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id,
            @Valid @RequestBody UserDto userDto) {
        return ResponseEntity.ok(userService.updateUser(id, userDto));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id) {
        userService.deleteUser(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{id}/notifications")
    public ResponseEntity<List<NotificationDto>> getUserNotifications(
            @PathVariable @Positive(message = "User ID must be a positive number") Long id) {
        userService.getUserById(id); // validates user exists; throws 404 if not
        return ResponseEntity.ok(notificationClient.getNotificationsForUser(id));
    }
}
