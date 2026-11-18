package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.TokenResponse;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.token.TokenDetails;
import com.blogPlatform.III.blog.service.AuthService;
import com.blogPlatform.III.blog.service.UserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "Sign up, log in (JWT) and the current user")
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthService authService;

    @Operation(summary = "Sign up",
            description = "Creates a READER account. Username 3-50 chars (letters, digits . _ -), valid email, "
                    + "password 8-100 chars with a letter and a digit, stored as a BCrypt hash and never returned. "
                    + "A \"role\" field is refused. 400: invalid body. 409: username or email already used.")
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody UserRequest request) {
        return ApiResponse.created("User registered successfully", userService.register(request));
    }

    @Operation(summary = "Log in",
            description = "Returns a JWT signed with HMAC-SHA256. Send it on every other request as "
                    + "'Authorization: Bearer <accessToken>'. 401: wrong username or password.")
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<TokenResponse>> login(@Valid @RequestBody LoginRequest request,
                                                            HttpServletRequest httpRequest) {
        return ApiResponse.ok("Logged in successfully", authService.login(request, httpRequest.getRemoteAddr()));
    }

    @Operation(summary = "Log out",
            description = "Revokes the token of this request: from now on it gets 401 \"revoked\", even before it "
                    + "expires. Other tokens of the same user (other devices) keep working. 401: no valid token.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(Authentication authentication) {
        // the JWT filter put the token's id there; a request authenticated another way has nothing to revoke
        if (authentication.getDetails() instanceof TokenDetails token) {
            authService.logout(token);
        }
        return ApiResponse.ok("Logged out successfully", null);
    }

    @Operation(summary = "Current user",
            description = "The user of the token, read fresh from the database. "
                    + "401: no token, or an expired, changed or malformed one.")
    @PreAuthorize("isAuthenticated()")
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<UserResponse>> me(@AuthenticationPrincipal AuthUser me) {
        return ApiResponse.ok("Current user retrieved successfully", userService.getById(me.id()));
    }
}
