package com.enterprise.demo.security;

import com.enterprise.demo.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Stateless JWT security configuration.
 *
 * Authorization rules:
 *   PUBLIC        /api/v1/auth/**     — login, register, refresh, logout
 *                 /actuator/health/** — K8s probes
 *                 /h2-console/**      — dev H2 UI (further guarded by H2ConsoleConfig)
 *
 *   ADMIN only    DELETE /api/v1/users/**   — only admins may delete user accounts
 *                 DELETE /api/v1/files/**   — only admins may delete files
 *                 POST   /api/v1/users      — only admins may create users via the management API
 *                 PUT    /api/v1/users/**   — only admins may update user accounts
 *
 *   Authenticated everything else
 *
 * CustomAuthEntryPoint and CustomAccessDeniedHandler are instantiated directly in filterChain()
 * so that SecurityConfig has no mandatory @Component dependencies that are absent from the
 * @WebMvcTest slice — this keeps slice tests self-contained without extra mocks.
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new CustomAuthEntryPoint())
                .accessDeniedHandler(new CustomAccessDeniedHandler()))
            .authorizeHttpRequests(auth -> auth
                // ── public ──────────────────────────────────────────────────
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                .requestMatchers("/h2-console/**").permitAll()

                // ── ADMIN only ───────────────────────────────────────────────
                .requestMatchers(HttpMethod.DELETE, "/api/v1/users/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.DELETE, "/api/v1/files/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole("ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/**").hasRole("ADMIN")

                // ── any authenticated user ────────────────────────────────────
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
