package com.enterprise.demo.service;

import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.ExpiredResetTokenException;
import com.enterprise.demo.exception.InvalidResetTokenException;
import com.enterprise.demo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private EmailService emailService;
    @Mock private PasswordEncoder passwordEncoder;

    @InjectMocks private PasswordResetService passwordResetService;

    private User user;

    @BeforeEach
    void setUp() {
        user = new User("alice", "alice@example.com");
    }

    // ── initiateReset ─────────────────────────────────────────────────────────

    @Test
    void initiateReset_registeredEmail_generatesTokenAndNotifies() {
        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        passwordResetService.initiateReset("alice@example.com");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User persisted = saved.getValue();

        assertThat(persisted.getPasswordResetToken()).isNotBlank();
        assertThat(persisted.getPasswordResetTokenExpiry())
                .isAfter(Instant.now())
                .isBefore(Instant.now().plus(61, ChronoUnit.MINUTES));

        verify(emailService).sendPasswordResetEmail(eq("alice@example.com"), anyString());
    }

    @Test
    void initiateReset_unregisteredEmail_silentlyReturns() {
        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        passwordResetService.initiateReset("nobody@example.com");

        verify(userRepository, never()).save(any());
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void initiateReset_secondRequest_overwritesPreviousToken() {
        user.setPasswordResetToken("old-token");
        user.setPasswordResetTokenExpiry(Instant.now().plus(30, ChronoUnit.MINUTES));

        when(userRepository.findByEmail("alice@example.com")).thenReturn(Optional.of(user));
        when(userRepository.save(any(User.class))).thenReturn(user);

        passwordResetService.initiateReset("alice@example.com");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getPasswordResetToken()).isNotEqualTo("old-token");
    }

    // ── completeReset ─────────────────────────────────────────────────────────

    @Test
    void completeReset_validToken_updatesPasswordAndClearsFields() {
        user.setPasswordResetToken("valid-token");
        user.setPasswordResetTokenExpiry(Instant.now().plus(30, ChronoUnit.MINUTES));

        when(userRepository.findByPasswordResetToken("valid-token")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPass1!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(user);

        passwordResetService.completeReset("valid-token", "newPass1!");

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        User persisted = saved.getValue();

        assertThat(persisted.getPassword()).isEqualTo("hashed");
        assertThat(persisted.getPasswordResetToken()).isNull();
        assertThat(persisted.getPasswordResetTokenExpiry()).isNull();
    }

    @Test
    void completeReset_unknownToken_throwsInvalidResetTokenException() {
        when(userRepository.findByPasswordResetToken("bad-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.completeReset("bad-token", "newPass1!"))
                .isInstanceOf(InvalidResetTokenException.class)
                .hasMessage("Invalid reset token.");

        verify(userRepository, never()).save(any());
    }

    @Test
    void completeReset_expiredToken_throwsExpiredResetTokenException() {
        user.setPasswordResetToken("expired-token");
        user.setPasswordResetTokenExpiry(Instant.now().minus(1, ChronoUnit.HOURS));

        when(userRepository.findByPasswordResetToken("expired-token")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> passwordResetService.completeReset("expired-token", "newPass1!"))
                .isInstanceOf(ExpiredResetTokenException.class)
                .hasMessage("Reset token has expired.");

        verify(userRepository, never()).save(any());
    }

    @Test
    void completeReset_alreadyUsedToken_tokenClearedOnUserSoLooksUnknown() {
        // After a successful reset the token column is null — a second use is identical
        // to an unknown token from the repo's perspective.
        when(userRepository.findByPasswordResetToken("used-token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> passwordResetService.completeReset("used-token", "newPass1!"))
                .isInstanceOf(InvalidResetTokenException.class);
    }
}
