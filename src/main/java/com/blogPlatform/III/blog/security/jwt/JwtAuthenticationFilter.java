package com.blogPlatform.III.blog.security.jwt;

import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.SecurityErrorHandler;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

// Reads "Authorization: Bearer <token>" on every request, checks it, and tells Spring Security who is calling.
// Not a @Component on purpose: Spring Boot would also register every Filter bean for ALL requests, outside
// the security chain. SecurityConfig creates it and puts it in the chain.
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final SecurityErrorHandler errorHandler;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            // no token: continue as anonymous; the access rules decide whether that is enough
            chain.doFilter(request, response);
            return;
        }

        AuthUser user;
        try {
            user = jwtService.toAuthUser(jwtService.verify(header.substring(BEARER.length())));
        } catch (ExpiredJwtException e) {
            errorHandler.unauthorized(request, response, "Token has expired, please log in again");
            return;
        } catch (SignatureException e) {
            errorHandler.unauthorized(request, response, "Token signature is not valid");
            return;
        } catch (UnsupportedJwtException e) {
            errorHandler.unauthorized(request, response, "Unsigned tokens are not accepted");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            errorHandler.unauthorized(request, response, "Token is malformed or not valid");
            return;
        }

        // a fresh context for this request only; nothing is kept after it (STATELESS)
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities()));
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }
}
