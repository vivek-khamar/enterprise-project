package com.enterprise.demo.security;

import com.enterprise.demo.config.JwtProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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
import tools.jackson.databind.ObjectMapper;

import java.util.Arrays;

/**
 * Stateless JWT security configuration.
 *
 * ── CSRF ────────────────────────────────────────────────────────────────────
 * CSRF protection is intentionally DISABLED.  This is correct and safe for a
 * stateless REST API that authenticates via the "Authorization: Bearer <token>"
 * request header:
 *
 *   1. No HTTP session — SessionCreationPolicy.STATELESS means Spring Security
 *      never creates an HttpSession, so there is no session cookie to steal.
 *   2. Bearer tokens are not auto-sent — browsers only auto-include cookies and
 *      basic-auth credentials in cross-origin requests.  They never auto-include
 *      an "Authorization" header, so a CSRF attacker cannot trigger an
 *      authenticated request from a victim's browser.
 *   3. SameSite mitigations are irrelevant here — we have no cookies at all.
 *
 * If this API ever switches to cookie-based auth (e.g., httpOnly JWT cookies),
 * CSRF protection MUST be re-enabled and tokens added to every state-changing
 * request.
 *
 * ── SQL injection ────────────────────────────────────────────────────────────
 * All database access uses Spring Data JPA which generates PreparedStatements.
 * Custom @Query annotations in repositories use named parameters (:param) —
 * never string concatenation.  Native queries (nativeQuery=true) are not used
 * anywhere in the codebase.  SQL injection risk is therefore minimal.
 *
 * ── Rate limiting ────────────────────────────────────────────────────────────
 * RateLimitFilter (registered below, runs first) applies per-IP token buckets
 * on the auth endpoints.  See RateLimitFilter.java for the limit definitions.
 *
 * ── Authorization rules ───────────────────────────────────────────────────────
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
 */
@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(JwtProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private static final String ROLE_ADMIN = "ADMIN";

    private final JwtAuthFilter jwtAuthFilter;
    private final RateLimitFilter rateLimitFilter;
    /** Used to serialize 401/403 error bodies via proper Jackson escaping. */
    private final ObjectMapper objectMapper;
    private final Environment environment;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            // See class-level Javadoc for the rationale: stateless JWT API does not need CSRF.
            .csrf(csrf -> csrf.disable())
            // Explicit security headers (Spring Security adds these by default, but listing
            // them here makes the intent clear and prevents accidental disabling in future).
            .headers(headers -> headers
                .frameOptions(fo -> fo.deny())                   // X-Frame-Options: DENY
                .contentTypeOptions(cto -> {})                   // X-Content-Type-Options: nosniff
                // X-XSS-Protection is intentionally omitted — modern browsers ignore it,
                // and some versions caused vulnerabilities.  CSP is the correct defence.
            )
            .sessionManagement(session ->
                session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            // Handlers receive ObjectMapper so they use proper JSON escaping instead
            // of manual string concatenation.  Instantiated inline (not @Component beans)
            // so @WebMvcTest slices don't need to provide them as separate mocks.
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint(new CustomAuthEntryPoint(objectMapper))
                .accessDeniedHandler(new CustomAccessDeniedHandler(objectMapper)))
            .authorizeHttpRequests(auth -> auth
                // ── public ──────────────────────────────────────────────────
                .requestMatchers("/api/v1/auth/**").permitAll()
                .requestMatchers("/actuator/health/**").permitAll()
                // H2 console is only registered in dev (H2ConsoleConfig is @Profile("dev")).
                // Guard the security rule with the same profile so a misconfigured
                // non-dev deployment cannot accidentally expose a raw SQL shell.
                .requestMatchers("/h2-console/**")
                    .access((supplier, ctx) -> new org.springframework.security.authorization.AuthorizationDecision(
                        Arrays.asList(environment.getActiveProfiles()).contains("dev")))

                // ── ADMIN only ───────────────────────────────────────────────
                .requestMatchers(HttpMethod.DELETE, "/api/v1/users/**").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.DELETE, "/api/v1/files/**").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.POST, "/api/v1/users").hasRole(ROLE_ADMIN)
                .requestMatchers(HttpMethod.PUT, "/api/v1/users/**").hasRole(ROLE_ADMIN)

                // ── KYC: submit + own status visible to any authenticated user ──
                .requestMatchers(HttpMethod.POST, "/api/v1/kyc/submit").authenticated()
                .requestMatchers(HttpMethod.GET,  "/api/v1/kyc/me").authenticated()
                .requestMatchers("/api/v1/kyc/**").hasRole(ROLE_ADMIN)

                // ── Transactions: flagged list is ADMIN only ──────────────────
                .requestMatchers(HttpMethod.GET, "/api/v1/transactions/flagged").hasRole(ROLE_ADMIN)

                // ── any authenticated user ────────────────────────────────────
                .anyRequest().authenticated()
            )
            // addFilterBefore inserts each filter directly before the anchor; the last
            // registration sits closest to the anchor, so jwtAuthFilter must be registered
            // first so that rateLimitFilter ends up running first in the chain.
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(rateLimitFilter, UsernamePasswordAuthenticationFilter.class);

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
