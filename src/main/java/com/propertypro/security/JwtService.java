package com.propertypro.security;

import com.propertypro.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Short-lived access tokens (HS256). They carry the user's organisation, roles
 * and permission codes so no database lookup is needed per request.
 * Permission changes take effect when the token is next refreshed (max 15 min).
 */
@Service
public class JwtService {

    private static final String ISSUER = "propertypro";

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(AppProperties props) {
        byte[] secret = Decoders.BASE64.decode(props.jwt().secret());
        if (secret.length < 32) {
            throw new IllegalStateException("app.jwt.secret must be at least 32 bytes (base64-encoded)");
        }
        this.key = Keys.hmacShaKeyFor(secret);
        this.accessTtl = Duration.ofMinutes(props.jwt().accessTokenMinutes());
    }

    public String issueAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        var builder = Jwts.builder()
                .issuer(ISSUER)
                .subject(user.userId().toString())
                .claim("typ", user.userType())
                .claim("roles", user.roles())
                .claim("perms", user.permissions())
                .claim("pv", user.phoneVerified())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)));
        if (user.organizationId() != null) {
            builder.claim("org", user.organizationId().toString());
        }
        return builder.signWith(key).compact();
    }

    public long accessTokenSeconds() {
        return accessTtl.toSeconds();
    }

    /** Validates signature, issuer and expiry. Throws {@link JwtException} if invalid. */
    public AuthenticatedUser parse(String token) {
        Claims claims = Jwts.parser()
                .verifyWith(key)
                .requireIssuer(ISSUER)
                .build()
                .parseSignedClaims(token)
                .getPayload();
        String org = claims.get("org", String.class);
        return new AuthenticatedUser(
                UUID.fromString(claims.getSubject()),
                org == null ? null : UUID.fromString(org),
                claims.get("typ", String.class),
                stringList(claims.get("roles")),
                stringList(claims.get("perms")),
                !Boolean.FALSE.equals(claims.get("pv", Boolean.class)));   // tokens from before the claim existed
    }

    private static List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }
}
