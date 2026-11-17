package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public TokenResponse login(LoginRequest request) {
        // a wrong username or password throws BadCredentialsException -> 401 (GlobalExceptionHandler)
        Authentication authentication = authenticationManager.authenticate(
                UsernamePasswordAuthenticationToken.unauthenticated(request.username(), request.password()));

        AuthUser user = (AuthUser) authentication.getPrincipal();
        return TokenResponse.of(jwtService.issue(user), user);
    }
}
