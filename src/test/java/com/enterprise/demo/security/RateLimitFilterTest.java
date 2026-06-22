package com.enterprise.demo.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for RateLimitFilter.
 * Each test creates a fresh filter instance to avoid counter state leaking between cases.
 */
class RateLimitFilterTest {

    private RateLimitFilter filter;
    private FilterChain chain;

    @BeforeEach
    void setUp() {
        filter = new RateLimitFilter(new ObjectMapper());
        chain = mock(FilterChain.class);
    }

    private HttpServletRequest request(String path) {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn(path);
        when(req.getMethod()).thenReturn("POST");
        when(req.getRemoteAddr()).thenReturn("10.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        return req;
    }

    private HttpServletResponse response() throws Exception {
        HttpServletResponse res = mock(HttpServletResponse.class);
        when(res.getWriter()).thenReturn(new PrintWriter(new StringWriter()));
        return res;
    }

    // ── Happy path: requests within limit pass through ────────────────────────

    @Test
    void loginEndpoint_allowsRequestsWithinLimit() throws Exception {
        HttpServletRequest req = request("/enterprise/api/v1/auth/login");
        HttpServletResponse res = response();

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(5)).doFilter(req, res);
        verify(res, never()).setStatus(429);
    }

    @Test
    void registerEndpoint_allowsRequestsWithinLimit() throws Exception {
        HttpServletRequest req = request("/enterprise/api/v1/auth/register");
        HttpServletResponse res = response();

        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(3)).doFilter(req, res);
    }

    // ── Rate-limit enforcement ────────────────────────────────────────────────

    @Test
    void loginEndpoint_blocks6thRequest() throws Exception {
        HttpServletRequest req = request("/enterprise/api/v1/auth/login");
        HttpServletResponse res = response();

        // First 5 succeed
        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(req, res, chain);
        }
        // 6th is blocked
        filter.doFilterInternal(req, res, chain);

        verify(chain, times(5)).doFilter(req, res);   // chain called exactly 5 times
        verify(res).setStatus(429);
        verify(res).setHeader("Retry-After", "60");
    }

    @Test
    void registerEndpoint_blocks4thRequest() throws Exception {
        HttpServletRequest req = request("/enterprise/api/v1/auth/register");
        HttpServletResponse res = response();

        for (int i = 0; i < 3; i++) {
            filter.doFilterInternal(req, res, chain);
        }
        filter.doFilterInternal(req, res, chain);   // 4th

        verify(chain, times(3)).doFilter(req, res);
        verify(res).setStatus(429);
    }

    // ── Different IPs have independent counters ───────────────────────────────

    @Test
    void differentIps_haveIndependentCounters() throws Exception {
        HttpServletRequest reqA = mock(HttpServletRequest.class);
        when(reqA.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(reqA.getMethod()).thenReturn("POST");
        when(reqA.getRemoteAddr()).thenReturn("10.0.0.1");
        when(reqA.getHeader("X-Forwarded-For")).thenReturn(null);

        HttpServletRequest reqB = mock(HttpServletRequest.class);
        when(reqB.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(reqB.getMethod()).thenReturn("POST");
        when(reqB.getRemoteAddr()).thenReturn("10.0.0.2");
        when(reqB.getHeader("X-Forwarded-For")).thenReturn(null);

        HttpServletResponse res = response();

        // Exhaust limit for IP A
        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(reqA, res, chain);
        }

        // IP B should still be allowed
        filter.doFilterInternal(reqB, res, chain);

        verify(chain, times(6)).doFilter(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    // ── Non-POST requests are never rate-limited ─────────────────────────────

    @Test
    void getRequests_areNeverRateLimited() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req.getMethod()).thenReturn("GET");

        HttpServletResponse res = response();

        for (int i = 0; i < 100; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(100)).doFilter(req, res);
        verify(res, never()).setStatus(429);
    }

    // ── Non-auth paths are not rate-limited ──────────────────────────────────

    @Test
    void nonAuthPaths_areNotRateLimited() throws Exception {
        HttpServletRequest req = request("/api/v1/users");
        HttpServletResponse res = response();

        for (int i = 0; i < 100; i++) {
            filter.doFilterInternal(req, res, chain);
        }

        verify(chain, times(100)).doFilter(req, res);
        verify(res, never()).setStatus(429);
    }

    // ── X-Forwarded-For is used when present ─────────────────────────────────

    @Test
    void xForwardedFor_usedAsClientIp() throws Exception {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/v1/auth/login");
        when(req.getMethod()).thenReturn("POST");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.5, 10.0.0.1");

        HttpServletResponse res = response();

        for (int i = 0; i < 5; i++) {
            filter.doFilterInternal(req, res, chain);
        }
        filter.doFilterInternal(req, res, chain);  // 6th — should be blocked

        verify(chain, times(5)).doFilter(req, res);
        verify(res).setStatus(429);
    }

    // ── Response body contains retryAfter field ───────────────────────────────

    @Test
    void blockedResponse_containsRetryAfterHeader() throws Exception {
        HttpServletRequest req = request("/api/v1/auth/register");
        HttpServletResponse res = response();

        for (int i = 0; i < 4; i++) {   // 3 allowed + 1 blocked
            filter.doFilterInternal(req, res, chain);
        }

        verify(res).setHeader("Retry-After", "60");
    }
}
