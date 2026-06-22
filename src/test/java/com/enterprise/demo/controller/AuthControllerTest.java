package com.enterprise.demo.controller;

import com.enterprise.demo.dto.AuthResponse;
import com.enterprise.demo.dto.LoginRequest;
import com.enterprise.demo.dto.RefreshRequest;
import com.enterprise.demo.dto.RegisterRequest;
import com.enterprise.demo.exception.TokenException;
import com.enterprise.demo.exception.ExpiredResetTokenException;
import com.enterprise.demo.exception.InvalidResetTokenException;
import com.enterprise.demo.service.AuthService;
import com.enterprise.demo.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.service.AuditService;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtUtil jwtUtil;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private AuditService auditService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private PasswordResetService passwordResetService;

    private static final AuthResponse SAMPLE_RESPONSE =
            new AuthResponse("access.tok", "refresh.tok", "Bearer", 900L);

    // ── register ──────────────────────────────────────────────────────────────

    @Test
    void register_returns201WithTokens() throws Exception {
        when(authService.register(any(RegisterRequest.class))).thenReturn(SAMPLE_RESPONSE);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\",\"password\":\"Pass1234!\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access.tok"))
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.expiresIn").value(900));
    }

    @Test
    void register_returns400WhenPasswordTooShort() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"email\":\"j@example.com\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void register_returns400WhenFieldsMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"email\":\"bad\",\"password\":\"Pass1234!\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── login ─────────────────────────────────────────────────────────────────

    @Test
    void login_returns200WithTokens() throws Exception {
        when(authService.login(any(LoginRequest.class))).thenReturn(SAMPLE_RESPONSE);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"password\":\"Pass1234!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access.tok"))
                .andExpect(jsonPath("$.refreshToken").value("refresh.tok"));
    }

    @Test
    void login_returns401OnBadCredentials() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"jsmith\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_returns200WithNewTokenPair() throws Exception {
        AuthResponse refreshed = new AuthResponse("new.access", "new.refresh", "Bearer", 900L);
        when(authService.refresh(any(RefreshRequest.class))).thenReturn(refreshed);

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.tok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new.access"));
    }

    @Test
    void refresh_returns401OnInvalidToken() throws Exception {
        when(authService.refresh(any(RefreshRequest.class)))
                .thenThrow(new TokenException("Refresh token has expired"));

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"expired\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.details").value("Invalid or expired token"));
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_returns204() throws Exception {
        doNothing().when(authService).logout(any());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"refresh.tok\"}"))
                .andExpect(status().isNoContent());
    }

    @Test
    void logout_returns400WhenTokenMissing() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── forgot-password ───────────────────────────────────────────────────────

    @Test
    void forgotPassword_returns200WithGenericMessage() throws Exception {
        doNothing().when(passwordResetService).initiateReset(any());

        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"j@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message")
                        .value("If that email is registered, a reset link has been sent."));
    }

    @Test
    void forgotPassword_returns400WhenEmailBlank() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"));
    }

    @Test
    void forgotPassword_returns400WhenEmailInvalid() throws Exception {
        mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
    }

    // ── reset-password ────────────────────────────────────────────────────────

    @Test
    void resetPassword_returns200WithSuccessMessage() throws Exception {
        doNothing().when(passwordResetService).completeReset(any(), any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"some-uuid\",\"newPassword\":\"newPass1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Password reset successful."));
    }

    @Test
    void resetPassword_returns400WhenTokenInvalid() throws Exception {
        org.mockito.Mockito.doThrow(new InvalidResetTokenException())
                .when(passwordResetService).completeReset(any(), any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"bad-token\",\"newPassword\":\"newPass1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Invalid reset token."));
    }

    @Test
    void resetPassword_returns400WhenTokenExpired() throws Exception {
        org.mockito.Mockito.doThrow(new ExpiredResetTokenException())
                .when(passwordResetService).completeReset(any(), any());

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"expired-token\",\"newPassword\":\"newPass1!\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Reset token has expired."));
    }

    @Test
    void resetPassword_returns400WhenPasswordTooShort() throws Exception {
        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"some-uuid\",\"newPassword\":\"short\"}"))
                .andExpect(status().isBadRequest());
    }
}
