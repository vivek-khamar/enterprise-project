package com.enterprise.demo.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "jwt")
@Getter
@Setter
public class JwtProperties {

    /** Base64-encoded HMAC-SHA256 signing key (≥ 256 bits / 32 bytes). */
    private String secret;

    /** Access-token lifetime in seconds (default 15 min). */
    private long accessTokenExpirySeconds = 900;

    /** Refresh-token lifetime in seconds (default 7 days). */
    private long refreshTokenExpirySeconds = 604_800;
}
