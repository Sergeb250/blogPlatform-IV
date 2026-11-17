package com.blogPlatform.III.blog.security.jwt;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.security.AuthUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class JwtService {

    static final String USER_ID = "uid";
    static final String ROLES = "roles";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        // HS256 needs a key of at least 256 bits: a shorter secret fails here, at startup, not at the first login
        this.key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.secret()));
    }

    public IssuedToken issue(AuthUser user) {
        // JWT times are whole seconds; cutting here keeps expiresAt equal to the "exp" claim
        Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        Instant expiresAt = issuedAt.plus(properties.expiration());

        String token = Jwts.builder()
                // jti: a unique id per token, so one token can be revoked without touching the others
                .id(UUID.randomUUID().toString())
                .issuer(properties.issuer())
                .subject(user.username())
                .claim(USER_ID, user.id())
                .claim(ROLES, user.roles().stream().map(Enum::name).toList())
                .issuedAt(Date.from(issuedAt))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, issuedAt, expiresAt);
    }

    // checks the signature, the expiry and the issuer; throws a JwtException subclass if any of them is wrong
    public Claims verify(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .requireIssuer(properties.issuer())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    // no database call: a valid signature proves we wrote these claims ourselves
    public AuthUser toAuthUser(Claims claims) {
        List<?> roles = claims.get(ROLES, List.class);
        Set<RoleName> roleNames = EnumSet.noneOf(RoleName.class);
        if (roles != null) {
            roles.forEach(role -> roleNames.add(RoleName.valueOf(role.toString())));
        }
        return new AuthUser(claims.get(USER_ID, Long.class), claims.getSubject(), null, roleNames);
    }
}
