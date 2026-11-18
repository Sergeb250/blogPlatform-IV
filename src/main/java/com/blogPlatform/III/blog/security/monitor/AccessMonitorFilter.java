package com.blogPlatform.III.blog.security.monitor;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Counts every API request per user and per endpoint (access frequency in the security report).
// Placed right after JwtAuthenticationFilter, so the caller is already known. Not a @Component, for the same
// reason as JwtAuthenticationFilter: Spring Boot would also run it outside the security chain.
@RequiredArgsConstructor
public class AccessMonitorFilter extends OncePerRequestFilter {

    private final SecurityMonitor monitor;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            monitor.recordRequest(SecurityMonitor.currentUsername(), request.getMethod(), request.getRequestURI());
        }
        chain.doFilter(request, response);
    }
}
