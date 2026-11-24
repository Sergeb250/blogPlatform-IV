package com.blogPlatform.III.blog.security.monitor;

import com.blogPlatform.III.blog.aop.SecurityEventAspect;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Not a @Component on purpose, like JwtAuthenticationFilter: it must run only inside the security chain
@RequiredArgsConstructor
public class AccessMonitorFilter extends OncePerRequestFilter {

    private final SecurityEventAspect securityEvents;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getRequestURI().startsWith("/api/")) {
            securityEvents.recordRequest(SecurityEventAspect.currentUsername(), request.getRemoteAddr(),
                    request.getMethod(), request.getRequestURI());
        }
        chain.doFilter(request, response);
    }
}
