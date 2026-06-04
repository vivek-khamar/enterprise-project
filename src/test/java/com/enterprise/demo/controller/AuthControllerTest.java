package com.enterprise.demo.controller;

import com.enterprise.demo.dto.AuthResponse;
import com.enterprise.demo.dto.LoginRequest;
import com.enterprise.demo.dto.RefreshRequest;
import com.enterprise.demo.dto.RegisterRequest;
import com.enterprise.demo.exception.TokenException;
import com.enterprise.demo.service.AuthService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import com.enterprise.demo.security.JwtUtil;
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
    private AuthService authService;

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
                .andExpect(jsonPath("$.details").value("Refresh token has expired"));
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
}
