package com.blogPlatform.III.blog.security.oauth2;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.SecurityErrorHandler;
import com.blogPlatform.III.blog.service.AuthService;
import com.blogPlatform.III.blog.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

@Component
@RequiredArgsConstructor
public class GoogleLoginHandler implements AuthenticationSuccessHandler, AuthenticationFailureHandler {

    private final UserService userService;
    private final AuthService authService;
    private final SecurityErrorHandler errorHandler;
    private final ObjectMapper objectMapper;
    private final OAuth2Properties properties;

    @Override
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                        Authentication authentication) throws IOException {
        OAuth2User google = (OAuth2User) authentication.getPrincipal();
        String email = google.getAttribute("email");
        // Object, not passed straight into String.valueOf: getAttribute is generic, and Java would pick valueOf(char[])
        Object verified = google.getAttribute("email_verified");

        // only an address Google has checked may open (or create) the account with that email
        if (email == null || !Boolean.parseBoolean(String.valueOf(verified))) {
            errorHandler.rejectGoogleLogin(request, response, "the Google account has no verified email");
            return;
        }

        AuthUser user = userService.findOrCreateGoogleUser(google.getName(), email);
        TokenResponse token = authService.loginWithGoogle(user, request.getRemoteAddr());

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        if (properties.hasRedirect()) {
            // in the #fragment: a browser never sends it to any server, so the token stays out of server logs
            response.sendRedirect(properties.redirectUri() + "#access_token=" + token.accessToken());
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.ok("Logged in with Google", token).getBody());
    }

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException {
        errorHandler.rejectGoogleLogin(request, response, exception.getMessage());
    }
}
