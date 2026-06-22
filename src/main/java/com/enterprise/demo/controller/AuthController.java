package com.enterprise.demo.controller;

import com.enterprise.demo.dto.AuthResponse;
import com.enterprise.demo.dto.ForgotPasswordRequest;
import com.enterprise.demo.dto.LoginRequest;
import com.enterprise.demo.dto.MessageResponse;
import com.enterprise.demo.dto.RefreshRequest;
import com.enterprise.demo.dto.RegisterRequest;
import com.enterprise.demo.dto.ResetPasswordRequest;
import com.enterprise.demo.service.AuthService;
import com.enterprise.demo.service.PasswordResetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

    /** Self-registration — creates a USER-role account and returns tokens. */
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(authService.register(request));
    }

    /** Authenticate and receive an access token + refresh token. */
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(authService.login(request));
    }

    /** Exchange a valid refresh token for a new token pair (rotation). */
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return ResponseEntity.ok(authService.refresh(request));
    }

    /** Revoke the refresh token — the user must log in again after expiry. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@Valid @RequestBody RefreshRequest request) {
        authService.logout(request.getRefreshToken());
        return ResponseEntity.noContent().build();
    }

    /** Initiate password reset — sends a token to the registered email (logged in dev). */
    @PostMapping("/forgot-password")
    public ResponseEntity<MessageResponse> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request) {
        passwordResetService.initiateReset(request.getEmail());
        return ResponseEntity.ok(
                new MessageResponse("If that email is registered, a reset link has been sent."));
    }

    /** Complete password reset — exchanges a valid token for a new password. */
    @PostMapping("/reset-password")
    public ResponseEntity<MessageResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request) {
        passwordResetService.completeReset(request.getToken(), request.getNewPassword());
        return ResponseEntity.ok(new MessageResponse("Password reset successful."));
    }
}
