package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.security.monitor.SecurityMonitor;
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

// 401 and 403 from the security filters, in the same JSON shape as every other error of the API.
// GlobalExceptionHandler can't answer these: they happen before the request reaches a controller.
@Component
@RequiredArgsConstructor
public class SecurityErrorHandler implements AuthenticationEntryPoint, AccessDeniedHandler {

    public static final String ACCESS_DENIED = "You do not have permission to do this";

    private final ObjectMapper objectMapper;
    private final SecurityMonitor monitor;

    // 401: the endpoint needs a login and the request has none
    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException exception) throws IOException {
        monitor.unauthorized(request);
        write(request, response, HttpStatus.UNAUTHORIZED,
                "Authentication required: log in and send 'Authorization: Bearer <token>'");
    }

    // 401 with the exact reason: a bad token (JwtAuthenticationFilter) or a failed Google login (GoogleLoginHandler)
    public void unauthorized(HttpServletRequest request, HttpServletResponse response, String reason) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, reason);
    }

    // 403: logged in, but the user's role is not allowed here
    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException exception) throws IOException {
        monitor.forbidden(request);
        write(request, response, HttpStatus.FORBIDDEN, ACCESS_DENIED);
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
