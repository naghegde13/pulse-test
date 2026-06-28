package com.pulse.auth.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {

    private final SecretKey key;
    private final long accessTokenExpiry;

    public JwtService(
            @Value("${pulse.jwt.secret:pulse-dev-secret-key-change-in-production-minimum-256-bits!!}") String secret,
            @Value("${pulse.jwt.access-token-expiry:28800}") long accessTokenExpiry) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.accessTokenExpiry = accessTokenExpiry;
    }

    public String generateToken(String userId, String email, String tenantId,
                                 String role, List<String> permissions) {
        return generateToken(userId, email, email, tenantId, role, permissions);
    }

    public String generateToken(String userId, String email, String displayName, String tenantId,
                                 String role, List<String> permissions) {
        return Jwts.builder()
                .subject(userId)
                .claims(Map.of(
                        "email", email,
                        "displayName", displayName,
                        "tenantId", tenantId,
                        "role", role,
                        "permissions", permissions
                ))
                .issuedAt(Date.from(Instant.now()))
                .expiration(Date.from(Instant.now().plusSeconds(accessTokenExpiry)))
                .signWith(key)
                .compact();
    }

    public Claims parseToken(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isValid(String token) {
        try {
            parseToken(token);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
