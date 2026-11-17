package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.IssuedToken;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, Instant expiresAt,
                            String username, Set<RoleName> roles) {

    public static TokenResponse of(IssuedToken token, AuthUser user) {
        long seconds = Duration.between(token.issuedAt(), token.expiresAt()).toSeconds();
        return new TokenResponse(token.value(), "Bearer", seconds, token.expiresAt(), user.username(), user.roles());
    }
}
