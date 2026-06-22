package com.enterprise.demo.service;

import com.enterprise.demo.config.JwtProperties;
import com.enterprise.demo.dto.AuthResponse;
import com.enterprise.demo.dto.LoginRequest;
import com.enterprise.demo.dto.RefreshRequest;
import com.enterprise.demo.dto.RegisterRequest;
import com.enterprise.demo.entity.RefreshToken;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.TokenException;
import com.enterprise.demo.repository.RefreshTokenRepository;
import com.enterprise.demo.repository.UserRepository;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import com.enterprise.demo.service.AuditService;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RefreshTokenRepository refreshTokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private AuthenticationManager authenticationManager;
    @Mock private UserDetailsService userDetailsService;
    @Mock private JwtProperties jwtProperties;

    @Mock
    private AuditService auditService;

    @InjectMocks private AuthService authService;

    private User testUser;
    private UserDetails testUserDetails;

    @BeforeEach
    void setUp() {
        testUser = new User("jsmith", "j@example.com");
        testUser.setId(1L);
        testUser.setRole(Role.USER);

        testUserDetails = org.springframework.security.core.userdetails.User
                .withUsername("jsmith")
                .password("hashed")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();

        lenient().when(jwtProperties.getAccessTokenExpirySeconds()).thenReturn(900L);
        lenient().when(jwtProperties.getRefreshTokenExpirySeconds()).thenReturn(604_800L);
    }

    // ── register ─────────────────────────────────────────────────────────────

    @Test
    void register_savesUserAndReturnsTokens() {
        RegisterRequest req = new RegisterRequest("jsmith", "j@example.com", "Pass1234!");
        when(userRepository.existsByUsername("jsmith")).thenReturn(false);
        when(userRepository.existsByEmail("j@example.com")).thenReturn(false);
        when(passwordEncoder.encode("Pass1234!")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(testUser);
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(testUserDetails);
        when(jwtUtil.generateAccessToken(testUserDetails, 1L)).thenReturn("access.token");
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.register(req);

        assertThat(response.getAccessToken()).isEqualTo("access.token");
        assertThat(response.getRefreshToken()).isNotBlank();
        assertThat(response.getTokenType()).isEqualTo("Bearer");
        assertThat(response.getExpiresIn()).isEqualTo(900L);
    }

    @Test
    void register_throwsWhenUsernameAlreadyExists() {
        when(userRepository.existsByUsername("jsmith")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("jsmith", "j@example.com", "Pass1234!")))
                .isInstanceOf(DataIntegrityViolationException.class);

        verify(userRepository, never()).save(any());
    }

    @Test
    void register_throwsWhenEmailAlreadyExists() {
        when(userRepository.existsByUsername("jsmith")).thenReturn(false);
        when(userRepository.existsByEmail("j@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(new RegisterRequest("jsmith", "j@example.com", "Pass1234!")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ── login ────────────────────────────────────────────────────────────────

    @Test
    void login_returnsTokensOnValidCredentials() {
        when(userRepository.findByUsername("jsmith")).thenReturn(Optional.of(testUser));
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(testUserDetails);
        when(jwtUtil.generateAccessToken(testUserDetails, 1L)).thenReturn("access.token");
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.login(new LoginRequest("jsmith", "Pass1234!"));

        assertThat(response.getAccessToken()).isEqualTo("access.token");
        assertThat(response.getRefreshToken()).isNotBlank();
        verify(authenticationManager).authenticate(any(UsernamePasswordAuthenticationToken.class));
    }

    @Test
    void login_throwsOnBadCredentials() {
        when(authenticationManager.authenticate(any()))
                .thenThrow(new BadCredentialsException("Bad credentials"));

        assertThatThrownBy(() -> authService.login(new LoginRequest("jsmith", "wrong")))
                .isInstanceOf(BadCredentialsException.class);
    }

    @Test
    void login_throwsResourceNotFound_whenUserDisappearsAfterAuthentication() {
        // Rare edge case: user is deleted between authentication and DB lookup.
        // Exercises the orElseThrow lambda in AuthService.login().
        when(userRepository.findByUsername("jsmith")).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequest("jsmith", "Pass1234!")))
                .isInstanceOf(com.enterprise.demo.exception.ResourceNotFoundException.class)
                .hasMessageContaining("jsmith");
    }

    // ── refresh ───────────────────────────────────────────────────────────────

    @Test
    void refresh_returnsNewTokenPairAndRevokesOldToken() {
        RefreshToken storedToken = buildActiveRefreshToken("old-token");
        when(refreshTokenRepository.findByToken("old-token")).thenReturn(Optional.of(storedToken));
        when(userDetailsService.loadUserByUsername("jsmith")).thenReturn(testUserDetails);
        when(jwtUtil.generateAccessToken(testUserDetails, 1L)).thenReturn("new.access.token");
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        AuthResponse response = authService.refresh(new RefreshRequest("old-token"));

        assertThat(response.getAccessToken()).isEqualTo("new.access.token");
        assertThat(storedToken.isRevoked()).isTrue();
    }

    @Test
    void refresh_throwsWhenTokenIsRevoked() {
        RefreshToken storedToken = buildActiveRefreshToken("token");
        storedToken.setRevoked(true);
        when(refreshTokenRepository.findByToken("token")).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("token")))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    void refresh_throwsWhenTokenIsExpired() {
        RefreshToken storedToken = buildActiveRefreshToken("token");
        storedToken.setExpiresAt(Instant.now().minusSeconds(1));
        when(refreshTokenRepository.findByToken("token")).thenReturn(Optional.of(storedToken));

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("token")))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("expired");
    }

    @Test
    void refresh_throwsWhenTokenNotFound() {
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh(new RefreshRequest("unknown")))
                .isInstanceOf(TokenException.class)
                .hasMessageContaining("not found");
    }

    // ── logout ────────────────────────────────────────────────────────────────

    @Test
    void logout_revokesRefreshToken() {
        RefreshToken storedToken = buildActiveRefreshToken("token");
        when(refreshTokenRepository.findByToken("token")).thenReturn(Optional.of(storedToken));
        when(refreshTokenRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        authService.logout("token");

        assertThat(storedToken.isRevoked()).isTrue();
        verify(refreshTokenRepository).save(storedToken);
    }

    @Test
    void logout_doesNothingWhenTokenNotFound() {
        when(refreshTokenRepository.findByToken(anyString())).thenReturn(Optional.empty());

        authService.logout("unknown");

        verify(refreshTokenRepository, never()).save(any());
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private RefreshToken buildActiveRefreshToken(String token) {
        RefreshToken rt = new RefreshToken();
        rt.setToken(token);
        rt.setUser(testUser);
        rt.setExpiresAt(Instant.now().plusSeconds(3600));
        rt.setRevoked(false);
        return rt;
    }
}
