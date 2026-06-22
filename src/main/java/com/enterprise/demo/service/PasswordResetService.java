package com.enterprise.demo.service;

import com.enterprise.demo.exception.ExpiredResetTokenException;
import com.enterprise.demo.exception.InvalidResetTokenException;
import com.enterprise.demo.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PasswordResetService {

    private final UserRepository userRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void initiateReset(String email) {
        userRepository.findByEmail(email).ifPresent(user -> {
            String token = UUID.randomUUID().toString();
            user.setPasswordResetToken(token);
            user.setPasswordResetTokenExpiry(Instant.now().plus(1, ChronoUnit.HOURS));
            userRepository.save(user);
            emailService.sendPasswordResetEmail(email, token);
        });
        // No error for unregistered email — prevents user enumeration.
    }

    @Transactional
    public void completeReset(String token, String newPassword) {
        var user = userRepository.findByPasswordResetToken(token)
                .orElseThrow(InvalidResetTokenException::new);

        if (user.getPasswordResetTokenExpiry().isBefore(Instant.now())) {
            throw new ExpiredResetTokenException();
        }

        user.setPassword(passwordEncoder.encode(newPassword));
        user.setPasswordResetToken(null);
        user.setPasswordResetTokenExpiry(null);
        userRepository.save(user);
    }
}
