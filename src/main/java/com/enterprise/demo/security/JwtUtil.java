package com.enterprise.demo.security;

import com.enterprise.demo.config.JwtProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.DeserializationException;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.SerializationException;
import io.jsonwebtoken.io.Serializer;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.SecretKey;
import java.io.OutputStream;
import java.io.Reader;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * Generates and validates HS256 JWTs.
 *
 * jjwt-jackson is intentionally excluded to avoid pulling in Jackson 2.x alongside
 * Spring Boot 4's Jackson 3.x.  io.jsonwebtoken.io.Serializer/Deserializer each have
 * two abstract methods so they cannot be lambda-assigned; anonymous classes are used
 * and delegated to the Spring-managed tools.jackson ObjectMapper.
 */
@Slf4j
@Component
public class JwtUtil {

    private final SecretKey signingKey;
    private final long accessTokenExpiryMs;
    private final Serializer<Map<String, ?>> claimSerializer;
    private final Deserializer<Map<String, ?>> claimDeserializer;

    public JwtUtil(JwtProperties props, ObjectMapper objectMapper) {
        byte[] keyBytes = Base64.getDecoder().decode(props.getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTokenExpiryMs = props.getAccessTokenExpirySeconds() * 1_000L;

        this.claimSerializer = new Serializer<Map<String, ?>>() {
            @Override
            public byte[] serialize(Map<String, ?> m) throws SerializationException {
                try {
                    return objectMapper.writeValueAsBytes(m);
                } catch (Exception e) {
                    throw new SerializationException("Cannot serialize JWT claims", e);
                }
            }

            @Override
            public void serialize(Map<String, ?> m, OutputStream out) throws SerializationException {
                try {
                    objectMapper.writeValue(out, m);
                } catch (Exception e) {
                    throw new SerializationException("Cannot serialize JWT claims to stream", e);
                }
            }
        };

        this.claimDeserializer = new Deserializer<Map<String, ?>>() {
            @Override
            @SuppressWarnings("unchecked")
            public Map<String, ?> deserialize(byte[] bytes) throws DeserializationException {
                try {
                    return (Map<String, ?>) objectMapper.readValue(bytes, Map.class);
                } catch (Exception e) {
                    throw new DeserializationException("Cannot deserialize JWT claims", e);
                }
            }

            @Override
            @SuppressWarnings("unchecked")
            public Map<String, ?> deserialize(Reader reader) throws DeserializationException {
                try {
                    return (Map<String, ?>) objectMapper.readValue(reader, Map.class);
                } catch (Exception e) {
                    throw new DeserializationException("Cannot deserialize JWT claims from reader", e);
                }
            }
        };
    }

    public String generateAccessToken(UserDetails userDetails, Long userId) {
        List<String> roles = userDetails.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();

        return Jwts.builder()
                .json(claimSerializer)
                .subject(userDetails.getUsername())
                .claim("roles", roles)
                .claim("userId", userId)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + accessTokenExpiryMs))
                .signWith(signingKey)
                .compact();
    }

    public Claims extractAllClaims(String token) {
        return Jwts.parser()
                .json(claimDeserializer)
                .verifyWith(signingKey)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public String extractUsername(String token) {
        return extractAllClaims(token).getSubject();
    }

    public boolean isTokenExpired(String token) {
        return extractAllClaims(token).getExpiration().before(new Date());
    }

    public boolean validateToken(String token, UserDetails userDetails) {
        try {
            String username = extractUsername(token);
            return username.equals(userDetails.getUsername()) && !isTokenExpired(token);
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("JWT validation failed: {}", e.getMessage());
            return false;
        }
    }
}
