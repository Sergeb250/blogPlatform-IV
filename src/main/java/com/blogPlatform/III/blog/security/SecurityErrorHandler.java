package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

// 401 / 403 from the security filters: they happen before any controller, so GlobalExceptionHandler can't answer them
@Component
@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String ACCESS_DENIED = "You do not have permission to do this";

    private final ObjectMapper objectMapper;

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication required: log in and send 'Authorization: Bearer <token>'");
    }

    // a bad, expired or revoked token (JwtAuthenticationFilter)
    public void rejectToken(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, reason);
    }

    // an unverified Google email, or a failed round trip to Google (GoogleLoginHandler)
    public void rejectGoogleLogin(HttpServletRequest request, HttpServletResponse response, String reason)
            throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "Google login failed: " + reason);
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, ACCESS_DENIED);
    }

    // 403 from the CSRF filter of the /csrf-demo chain: the form came without the hidden token, or with a wrong one
    public void rejectCsrf(HttpServletRequest request, HttpServletResponse response,
                           AccessDeniedException exception) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "Missing or invalid CSRF token");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String message) throws IOException {
        ApiResponse<Void> body = ApiResponse.error(status, message, request.getRequestURI()).getBody();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
