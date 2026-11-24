package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.exception.TooManyLoginAttemptsException;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.IssuedToken;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    // a BCrypt hash that matches no password. Unknown users and Google accounts (no password) are checked
    // against it, so a login takes the same time whether the username exists or not.
    private static final String NO_PASSWORD_HASH = "$2a$10$GUAdiFvCgzDzOOpdwiNuYuO43rFvcIGecNEUuR2sIrIpefSWPtQuu";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ActiveSessionRegistry sessions;
    private final LoginAttemptService loginAttempts;

    public TokenResponse login(LoginRequest request, String ip) {
        Duration wait = loginAttempts.retryAfter(request.username(), ip);
        if (!wait.isZero()) {
            throw new TooManyLoginAttemptsException(wait);
        }

        AuthUser user = userRepository.findByUsername(request.username()).map(AuthUser::from).orElse(null);
        boolean hasPassword = user != null && user.passwordHash() != null;
        String hash = hasPassword ? user.passwordHash() : NO_PASSWORD_HASH;

        if (!passwordEncoder.matches(request.password(), hash) || !hasPassword) {
            loginAttempts.loginFailed(request.username(), ip);
            throw new BadCredentialsException("Invalid username or password");
        }

        loginAttempts.loginSucceeded(request.username(), ip);
        return issueToken(user, ip);
    }

    public TokenResponse issueToken(AuthUser user, String ip) {
        IssuedToken token = jwtService.issue(user);
        sessions.register(token, user, ip);
        return TokenResponse.of(token, user);
    }

    public TokenResponse loginWithGoogle(AuthUser user, String ip) {
        return issueToken(user, ip);
    }

    public void logout(TokenDetails token, String username) {
        sessions.revoke(token.tokenId(), token.expiresAt());
    }
}
