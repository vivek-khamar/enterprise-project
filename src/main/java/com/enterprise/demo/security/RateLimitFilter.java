package com.enterprise.demo.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * Fixed-window rate limiter applied before authentication.
 *
 * Limits are enforced per client IP address on high-risk auth endpoints:
 *
 *   POST /api/v1/auth/login     — 5  requests / 60 s   (brute-force protection)
 *   POST /api/v1/auth/register  — 3  requests / 60 s   (account-creation spam protection)
 *   POST /api/v1/auth/refresh   — 10 requests / 60 s   (token-refresh abuse protection)
 *   POST /api/v1/auth/logout    — 10 requests / 60 s   (logout-storm protection)
 *
 * Output encoding: the 429 body is serialised via Jackson ObjectMapper, not manual
 * string concatenation, so all values are properly escaped.
 *
 * IP extraction: X-Forwarded-For is validated against a strict IPv4/IPv6 regex before
 * being used as a cache key or written to audit logs, preventing log-injection attacks
 * via a crafted XFF header.
 */
@Component
@Order(1)   // runs before the JWT filter
@RequiredArgsConstructor
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");

    /**
     * Strict allowlist for IP addresses extracted from X-Forwarded-For.
     * Rejects any value that is not a valid dotted-decimal IPv4 or compressed IPv6 address.
     * This prevents log-injection via a crafted X-Forwarded-For header such as
     * "127.0.0.1\n event=LOGIN_SUCCESS user=admin".
     */
    private static final Pattern VALID_IP = Pattern.compile(
            "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$" +     // IPv4
            "|^[0-9a-fA-F:]{2,39}$"                    // IPv6 (simplified; covers all valid forms)
    );

    /** Used to serialise 429 bodies via proper Jackson escaping. */
    private final ObjectMapper objectMapper;

    // ── Limit table: path suffix → [maxRequests, windowSeconds] ─────────────

    private static final Map<String, int[]> LIMITS = Map.of(
            "/auth/login",    new int[]{5,  60},
            "/auth/register", new int[]{3,  60},
            "/auth/refresh",  new int[]{10, 60},
            "/auth/logout",   new int[]{10, 60}
    );

    /**
     * Counter cache keyed by "ip::path".
     * Each entry expires after 60 s (the longest window), resetting the counter.
     * Inactive IPs are also evicted to cap memory at ~100 k entries.
     */
    private final Cache<String, AtomicInteger> counters = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(100_000)
            .build();

    // ── Filter logic ──────────────────────────────────────────────────────────

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String path = request.getRequestURI();
        int[] limit = matchedLimit(path, request.getMethod());

        if (limit != null) {
            String ip       = clientIp(request);
            String cacheKey = ip + "::" + path;

            AtomicInteger counter = counters.get(cacheKey, k -> new AtomicInteger(0));
            int current = counter.incrementAndGet();

            if (current > limit[0]) {
                AUDIT.info("event=RATE_LIMIT_EXCEEDED ip={} path={} count={} limit={}",
                        ip, path, current, limit[0]);
                rejectWith429(response, limit[1]);
                return;
            }
        }

        chain.doFilter(request, response);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static int[] matchedLimit(String path, String method) {
        if (!"POST".equalsIgnoreCase(method)) {
            return null;
        }
        for (Map.Entry<String, int[]> entry : LIMITS.entrySet()) {
            if (path.endsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    private void rejectWith429(HttpServletResponse response,
                               int retryAfterSeconds) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setHeader("Retry-After", String.valueOf(retryAfterSeconds));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("message", "Too Many Requests");
        body.put("details", "Rate limit exceeded. Please wait before retrying.");
        body.put("retryAfter", retryAfterSeconds);

        objectMapper.writeValue(response.getWriter(), body);
    }

    /**
     * Resolves the real client IP from the request.
     *
     * X-Forwarded-For is only trusted when the direct TCP connection originates
     * from a known private/loopback address (a load-balancer or ingress controller).
     * Accepting XFF from arbitrary clients allows spoofing that fully defeats the
     * per-IP rate limit — an attacker just rotates fake IPs in the header.
     *
     * When the direct caller is not a trusted proxy the TCP remote address is used
     * as the rate-limit key; it cannot be spoofed at the TCP layer.
     */
    static String clientIp(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (isTrustedProxy(remoteAddr)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String candidate = xff.split(",")[0].trim();
                if (VALID_IP.matcher(candidate).matches()) {
                    return candidate;
                }
                // XFF contained non-IP content — possible log-injection attempt.
                // Fall through to the TCP remote address.
            }
        }
        return remoteAddr;
    }

    /**
     * Returns true for loopback and RFC-1918 private addresses that are expected
     * to be deployed as reverse proxies / load-balancers in front of this service.
     */
    private static boolean isTrustedProxy(String addr) {
        if (addr == null) return false;
        return addr.equals("127.0.0.1")
            || addr.equals("::1")
            || addr.startsWith("10.")
            || addr.startsWith("192.168.")
            || addr.matches("^172\\.(1[6-9]|2\\d|3[01])\\..*");
    }
}
