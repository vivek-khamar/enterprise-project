package com.enterprise.demo.service;

import com.enterprise.demo.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/**
 * Periodically purges expired and revoked refresh tokens from the database.
 *
 * Data-minimisation rationale (GDPR Art.5(1)(e)):
 *   Refresh tokens contain a reference to an identifiable user (FK to users.id).
 *   They constitute personal data and must not be retained beyond their purpose.
 *   Expired tokens serve no purpose — a client holding one cannot use it — so they
 *   must be deleted once the expiry window has passed.
 *
 * Schedule: every day at 02:00 server time.  Adjust via
 *   spring.task.scheduling.token-cleanup.cron in each profile's application YAML
 *   if a different window is required (e.g. hourly in high-traffic deployments).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TokenCleanupScheduler {

    private final RefreshTokenRepository refreshTokenRepository;

    /**
     * Deletes refresh tokens whose {@code expiresAt} is in the past.
     * Revoked-and-not-yet-expired tokens are left in place so that concurrent
     * revocation checks (refresh endpoint) still work during the rotation window;
     * they will be cleaned up on the next run after they expire naturally.
     */
    @Scheduled(cron = "${token.cleanup.cron:0 0 2 * * *}")
    @Transactional
    public void purgeExpiredTokens() {
        Instant now = Instant.now();
        refreshTokenRepository.deleteExpired(now);
        log.info("Token cleanup completed — purged expired refresh tokens as of {}", now);
    }
}
