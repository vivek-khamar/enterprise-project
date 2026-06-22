package com.enterprise.demo.service;

import com.enterprise.demo.service.AuditService.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditServiceTest {

    private AuditService auditService;

    @BeforeEach
    void setUp() {
        auditService = new AuditService();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    // ── clientIp ─────────────────────────────────────────────────────────────

    @Test
    void clientIp_returnsRemoteAddr_whenNoXForwardedFor() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(auditService.clientIp()).isEqualTo("10.0.0.1");
    }

    @Test
    void clientIp_returnsFirstForwardedIp_whenXForwardedForPresent() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1, 192.168.1.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        assertThat(auditService.clientIp()).isEqualTo("203.0.113.5");
    }

    @Test
    void clientIp_returnsNoRequestContext_whenOutsideHttpRequest() {
        // No RequestContextHolder set — simulates scheduled task or unit test
        assertThat(auditService.clientIp()).isEqualTo("no-request-context");
    }

    // ── currentPrincipal ─────────────────────────────────────────────────────

    @Test
    void currentPrincipal_returnsUsername_whenAuthenticated() {
        var auth = new UsernamePasswordAuthenticationToken(
                "jsmith", null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        assertThat(auditService.currentPrincipal()).isEqualTo("jsmith");
    }

    @Test
    void currentPrincipal_returnsAnonymous_whenNotAuthenticated() {
        assertThat(auditService.currentPrincipal()).isEqualTo("anonymous");
    }

    // ── log (smoke — verifies no exception thrown) ────────────────────────────

    @Test
    void log_doesNotThrow_withValidArgs() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        // Should not throw regardless of SecurityContext state
        auditService.log(Event.LOGIN_SUCCESS, "jsmith", "role=USER");
        auditService.log(Event.LOGIN_FAILURE, "jsmith", null);
        auditService.log(Event.LOGOUT, null, null);      // null user → sanitized to "-"
    }

    @Test
    void logAdminAction_doesNotThrow_withValidArgs() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("127.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));

        var auth = new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        auditService.logAdminAction(Event.USER_DELETED, "42", "username=victim");
        auditService.logAdminAction(Event.USER_CREATED, null, null); // null target → "-"
    }
}
