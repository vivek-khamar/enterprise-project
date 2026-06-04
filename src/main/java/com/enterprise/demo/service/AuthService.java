package com.enterprise.demo.service;

import com.enterprise.demo.config.JwtProperties;
import com.enterprise.demo.dto.AuthResponse;
import com.enterprise.demo.dto.LoginRequest;
import com.enterprise.demo.dto.RefreshRequest;
import com.enterprise.demo.dto.RegisterRequest;
import com.enterprise.demo.entity.RefreshToken;
import com.enterprise.demo.entity.User;
import com.enterprise.demo.exception.ResourceNotFoundException;
import com.enterprise.demo.exception.TokenException;
import com.enterprise.demo.repository.RefreshTokenRepository;
import com.enterprise.demo.repository.UserRepository;
import com.enterprise.demo.security.JwtUtil;
import com.enterprise.demo.security.Role;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final AuthenticationManager authenticationManager;
    private final UserDetailsService userDetailsService;
    private final JwtProperties jwtProperties;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new DataIntegrityViolationException("Username already taken: " + request.getUsername());
        }
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new DataIntegrityViolationException("Email already registered: " + request.getEmail());
        }

        User user = new User();
        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.USER);
        user.setEnabled(true);

        User saved = userRepository.save(user);
        return buildAuthResponse(saved);
    }

    @Transactional
    public AuthResponse login(LoginRequest request) {
        authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(request.getUsername(), request.getPassword()));

        User user = userRepository.findByUsername(request.getUsername())
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + request.getUsername()));

        return buildAuthResponse(user);
    }

    @Transactional
    public AuthResponse refresh(RefreshRequest request) {
        RefreshToken storedToken = refreshTokenRepository.findByToken(request.getRefreshToken())
                .orElseThrow(() -> new TokenException("Refresh token not found"));

        if (storedToken.isRevoked()) {
            throw new TokenException("Refresh token has been revoked");
        }
        if (storedToken.getExpiresAt().isBefore(Instant.now())) {
            throw new TokenException("Refresh token has expired");
        }

        // Rotate: revoke old token, issue new pair
        storedToken.setRevoked(true);
        refreshTokenRepository.save(storedToken);

        return buildAuthResponse(storedToken.getUser());
    }

    @Transactional
    public void logout(String rawRefreshToken) {
        refreshTokenRepository.findByToken(rawRefreshToken).ifPresent(rt -> {
            rt.setRevoked(true);
            refreshTokenRepository.save(rt);
        });
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private AuthResponse buildAuthResponse(User user) {
        UserDetails userDetails = userDetailsService.loadUserByUsername(user.getUsername());
        String accessToken = jwtUtil.generateAccessToken(userDetails, user.getId());
        String refreshToken = createRefreshToken(user);
        return new AuthResponse(accessToken, refreshToken, "Bearer",
                jwtProperties.getAccessTokenExpirySeconds());
    }

    private String createRefreshToken(User user) {
        String token = UUID.randomUUID().toString();

        RefreshToken rt = new RefreshToken();
        rt.setToken(token);
        rt.setUser(user);
        rt.setExpiresAt(Instant.now().plusSeconds(jwtProperties.getRefreshTokenExpirySeconds()));
        rt.setRevoked(false);
        refreshTokenRepository.save(rt);

        return token;
    }
}
