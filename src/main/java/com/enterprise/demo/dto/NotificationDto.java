package com.enterprise.demo.dto;

import java.time.Instant;

public record NotificationDto(
        Long id,
        Long userId,
        String username,
        String message,
        String eventType,
        Instant createdAt
) {}
