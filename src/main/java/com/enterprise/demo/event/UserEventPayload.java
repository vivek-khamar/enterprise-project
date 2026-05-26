package com.enterprise.demo.event;

/**
 * The data carried inside every user event.
 * For USER_DELETED events the username and email reflect the values at the time of deletion.
 */
public record UserEventPayload(Long userId, String username, String email) {
}
