package com.enterprise.demo.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@Profile("dev")
public class LoggingEmailService implements EmailService {

    @Override
    public void sendPasswordResetEmail(String toEmail, String token) {
        log.info("PASSWORD RESET TOKEN for {}: {}", toEmail, token);
    }
}
