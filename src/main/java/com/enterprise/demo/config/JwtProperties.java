package com.enterprise.demo.config;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@ConfigurationProperties(prefix = "jwt")
@Validated
@Getter
@Setter
public class JwtProperties {

    /** Base64-encoded HMAC-SHA256 signing key (≥ 256 bits / 32 bytes). */
    @NotBlank(message = "jwt.secret is required — set the JWT_SECRET environment variable (openssl rand -base64 32)")
    private String secret;

    /** Access-token lifetime in seconds (default 15 min). */
    private long accessTokenExpirySeconds = 900;

    /** Refresh-token lifetime in seconds (default 7 days). */
    private long refreshTokenExpirySeconds = 604_800;
}
