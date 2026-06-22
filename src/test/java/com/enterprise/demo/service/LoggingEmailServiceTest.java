package com.enterprise.demo.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class LoggingEmailServiceTest {

    private final LoggingEmailService service = new LoggingEmailService();

    @Test
    void sendPasswordResetEmail_doesNotThrow() {
        assertThatCode(() -> service.sendPasswordResetEmail("user@example.com", "abc-token-123"))
                .doesNotThrowAnyException();
    }
}
