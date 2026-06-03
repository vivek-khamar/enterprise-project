package com.enterprise.demo.client;

import com.enterprise.demo.dto.NotificationDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NotificationClientTest {

    private static final String BASE_URL = "http://notification-service";

    private MockRestServiceServer mockServer;
    private NotificationClient client;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        client = new NotificationClient(builder, BASE_URL);
    }

    @Test
    void getNotificationsForUser_returnsNotifications() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/notifications/user/1"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("""
                        [
                          {
                            "id": 1,
                            "userId": 1,
                            "username": "jsmith",
                            "message": "Welcome, jsmith! Your account has been created.",
                            "eventType": "USER_CREATED",
                            "createdAt": "2026-05-28T00:00:00Z"
                          }
                        ]
                        """, MediaType.APPLICATION_JSON));

        List<NotificationDto> result = client.getNotificationsForUser(1L);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).userId()).isEqualTo(1L);
        assertThat(result.get(0).username()).isEqualTo("jsmith");
        assertThat(result.get(0).eventType()).isEqualTo("USER_CREATED");
        mockServer.verify();
    }

    @Test
    void getNotificationsForUser_returnsEmptyList_whenNotificationServiceFails() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/notifications/user/1"))
                .andRespond(withServerError());

        List<NotificationDto> result = client.getNotificationsForUser(1L);

        assertThat(result).isEmpty();
    }

    @Test
    void getNotificationsForUser_returnsEmptyList_whenResponseIsEmpty() {
        mockServer.expect(requestTo(BASE_URL + "/api/v1/notifications/user/99"))
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<NotificationDto> result = client.getNotificationsForUser(99L);

        assertThat(result).isEmpty();
    }
}
