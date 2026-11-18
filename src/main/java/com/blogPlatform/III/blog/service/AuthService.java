package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.exception.TooManyLoginAttemptsException;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.IssuedToken;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import com.blogPlatform.III.blog.security.monitor.LoginAttemptService;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenDetails;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Service;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final ActiveSessionRegistry sessions;
    private final LoginAttemptService loginAttempts;

    public TokenResponse login(LoginRequest request, String ip) {
        // checked before the password: while blocked, even the right password gets 429, so guessing gains nothing
        Duration wait = loginAttempts.retryAfter(request.username(), ip);
        if (!wait.isZero()) {
            throw new TooManyLoginAttemptsException(wait);
        }

        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));
        } catch (AuthenticationException e) {
            loginAttempts.loginFailed(request.username(), ip);
            // a wrong username or password: BadCredentialsException -> 401 (GlobalExceptionHandler)
            throw e;
        }

        loginAttempts.loginSucceeded(request.username(), ip);
        return issueToken((AuthUser) authentication.getPrincipal(), ip);
    }

    // the one place a token is made (password login and Google login), so every token is tracked from the start
    public TokenResponse issueToken(AuthUser user, String ip) {
        IssuedToken token = jwtService.issue(user);
        sessions.register(token, user, ip);
        return TokenResponse.of(token, user);
    }

    public void logout(TokenDetails token) {
        sessions.revoke(token.tokenId(), token.expiresAt());
    }
}
