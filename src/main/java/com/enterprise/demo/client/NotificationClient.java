package com.enterprise.demo.client;

import com.enterprise.demo.dto.NotificationDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

@Slf4j
@Component
public class NotificationClient {

    private final RestClient restClient;

    public NotificationClient(RestClient.Builder builder,
                               @Value("${notification.service.url}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
    }

    public List<NotificationDto> getNotificationsForUser(Long userId) {
        try {
            return restClient.get()
                    .uri("/api/v1/notifications/user/{userId}", userId)
                    .retrieve()
                    .body(new ParameterizedTypeReference<>() {});
        } catch (Exception ex) {
            log.warn("Could not fetch notifications for userId={}: {}", userId, ex.getMessage());
            return List.of();
        }
    }
}
