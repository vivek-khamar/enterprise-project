package com.enterprise.demo.service;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.regex.Pattern;

/**
 * Centralised security audit logger.
 *
 * All events are written to the dedicated "SECURITY_AUDIT" logger which is
 * configured separately in logback-spring.xml (separate appender, JSON format)
 * so that audit records can be shipped to a SIEM independently of application logs.
 *
 * Format: event=<EVENT> user=<username|anonymous> ip=<ip> [key=value ...]
 *
 * Rules:
 *   ✓ Log WHO did WHAT from WHERE and whether it SUCCEEDED.
 *   ✗ Never log passwords, token values, or PII beyond the username.
 */
@Slf4j
@Service
public class AuditService {

    /** Dedicated audit logger — see logback-spring.xml for appender configuration. */
    private static final Logger AUDIT = LoggerFactory.getLogger("SECURITY_AUDIT");

    /**
     * Validated IP pattern for values extracted from X-Forwarded-For.
     * Any XFF value that does not match is discarded in favour of the TCP remote address.
     * This prevents log-injection via a crafted header such as:
     *   X-Forwarded-For: 1.2.3.4\nevent=LOGIN_SUCCESS user=admin
     */
    private static final Pattern VALID_IP = Pattern.compile(
            "^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$" +  // IPv4
            "|^[0-9a-fA-F:]{2,39}$"                 // IPv6 (simplified)
    );

    // ── Event catalogue ───────────────────────────────────────────────────────

    public enum Event {
        // Authentication lifecycle
        REGISTER_SUCCESS,
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        LOGOUT,

        // Token lifecycle
        TOKEN_REFRESHED,
        TOKEN_REFRESH_FAILED,
        TOKEN_EXPIRED,
        TOKEN_INVALID,

        // Admin actions — who did what to which resource
        USER_CREATED,
        USER_UPDATED,
        USER_DELETED,
        USER_ENABLED,
        USER_DISABLED,
        ROLE_CHANGED,
        FILE_DELETED
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Log an authentication or token event.
     *
     * @param event     the security event type
     * @param username  the subject of the event (use "unknown" when unavailable)
     * @param extra     optional extra key=value pairs; must never contain a secret value
     */
    public void log(Event event, String username, String extra) {
        AUDIT.info("event={} user={} ip={}{}",
                event,
                sanitize(username),
                clientIp(),
                extra != null && !extra.isBlank() ? " " + extra : "");
    }

    /**
     * Log a privileged (admin) mutation.
     * Captures both the admin performing the action and the resource being acted on.
     *
     * @param event    the admin action type
     * @param targetId the identifier of the resource being mutated
     * @param extra    optional extra key=value pairs
     */
    public void logAdminAction(Event event, String targetId, String extra) {
        String admin = currentPrincipal();
        AUDIT.info("event={} admin={} target={} ip={}{}",
                event,
                sanitize(admin),
                sanitize(targetId),
                clientIp(),
                extra != null && !extra.isBlank() ? " " + extra : "");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Resolves the client IP address, honouring the X-Forwarded-For header set by
     * reverse proxies and load balancers.
     *
     * X-Forwarded-For is validated against VALID_IP before use.  An invalid value
     * (e.g. one containing newlines or injection sequences) is silently discarded
     * in favour of the TCP remote address, which cannot be spoofed by the client.
     *
     * Returns "no-request-context" when called outside of an HTTP request
     * (e.g. scheduled tasks, unit tests).
     */
    String clientIp() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.currentRequestAttributes();
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String candidate = xff.split(",")[0].trim();
                if (VALID_IP.matcher(candidate).matches()) {
                    return candidate;
                }
                // Invalid XFF value — fall through to the TCP remote address
            }
            return req.getRemoteAddr();
        } catch (IllegalStateException e) {
            return "no-request-context";
        }
    }

    /**
     * Returns the username of the currently authenticated principal, or
     * "anonymous" when there is no authenticated user in the SecurityContext.
     */
    String currentPrincipal() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return (auth != null && auth.isAuthenticated()) ? auth.getName() : "anonymous";
    }

    /**
     * Replaces null or blank identifiers with a safe placeholder so that
     * the structured log line never contains empty fields.
     */
    private String sanitize(String value) {
        return (value == null || value.isBlank()) ? "-" : value;
    }
}
