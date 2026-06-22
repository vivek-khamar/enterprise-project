package com.enterprise.demo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Returns a structured JSON 401 when an unauthenticated request hits a protected endpoint.
 *
 * Output encoding: uses Jackson ObjectMapper.writeValue() so all values are escaped.
 * The exception message is intentionally NOT included in the response body — it can
 * contain user-controlled data (e.g. from a crafted JWT) that would create a
 * reflected-content injection risk.
 */
public class CustomAuthEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public CustomAuthEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", LocalDateTime.now().toString());
        body.put("message", "Unauthorized");
        body.put("details", "Authentication required");  // static — never ex.getMessage()

        objectMapper.writeValue(response.getWriter(), body);
    }
}
