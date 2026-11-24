package com.blogPlatform.III.blog.security.jwt;

import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.SecurityErrorHandler;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import com.blogPlatform.III.blog.security.token.TokenBlacklist;
import com.blogPlatform.III.blog.security.token.TokenDetails;
import io.jsonwebtoken.Claims;
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

// Not a @Component on purpose: Spring Boot would also run every Filter bean outside the security chain
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER = "Bearer ";

    private final JwtService jwtService;
    private final SecurityErrorHandler errorHandler;
    private final TokenBlacklist blacklist;
    private final ActiveSessionRegistry sessions;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER)) {
            chain.doFilter(request, response);
            return;
        }

        Claims claims;
        AuthUser user;
        try {
            claims = jwtService.verify(header.substring(BEARER.length()));
            user = jwtService.toAuthUser(claims);
        } catch (ExpiredJwtException e) {
            errorHandler.rejectToken(request, response, "Token has expired, please log in again");
            return;
        } catch (SignatureException e) {
            errorHandler.rejectToken(request, response, "Token signature is not valid");
            return;
        } catch (UnsupportedJwtException e) {
            errorHandler.rejectToken(request, response, "Unsigned tokens are not accepted");
            return;
        } catch (JwtException | IllegalArgumentException e) {
            errorHandler.rejectToken(request, response, "Token is malformed or not valid");
            return;
        }

        if (blacklist.isRevoked(claims.getId())) {
            errorHandler.rejectToken(request, response, "Token has been revoked, please log in again");
            return;
        }
        sessions.recordRequest(claims, request.getRemoteAddr());

        UsernamePasswordAuthenticationToken authentication =
                UsernamePasswordAuthenticationToken.authenticated(user, null, user.getAuthorities());
        authentication.setDetails(new TokenDetails(claims.getId(), claims.getExpiration().toInstant()));

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authentication);
        SecurityContextHolder.setContext(context);

        chain.doFilter(request, response);
    }
}
