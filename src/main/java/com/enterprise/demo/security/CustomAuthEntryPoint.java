package com.enterprise.demo.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/** Returns a JSON 401 when an unauthenticated request hits a protected endpoint. */
@Component
public class CustomAuthEntryPoint implements AuthenticationEntryPoint {

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException ex) throws IOException {
        response.setContentType("application/json");
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.getWriter().write(
                "{\"timestamp\":\"" + LocalDateTime.now() + "\"," +
                "\"message\":\"Unauthorized\"," +
                "\"details\":\"" + ex.getMessage() + "\"}");
    }
}
