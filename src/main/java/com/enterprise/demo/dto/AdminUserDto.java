package com.enterprise.demo.dto;

import com.enterprise.demo.entity.User;
import com.enterprise.demo.security.Role;

import java.time.Instant;

public record AdminUserDto(
        Long id,
        String username,
        String email,
        Role role,
        boolean enabled,
        Instant createdAt
) {
    public static AdminUserDto from(User user) {
        return new AdminUserDto(
                user.getId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                user.isEnabled(),
                user.getCreatedAt());
    }
}
