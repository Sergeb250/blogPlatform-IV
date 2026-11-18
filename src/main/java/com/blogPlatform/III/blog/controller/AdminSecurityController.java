package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.SessionResponse;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin: security", description = "Active sessions and forced logout (ADMIN)")
@RestController
@RequestMapping("/api/admin/security")
// the URL rule already allows /api/admin/** to ADMIN only; this repeats it next to the code (defense in depth)
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class AdminSecurityController {

    private final ActiveSessionRegistry sessions;

    @Operation(summary = "Active sessions",
            description = "Every token in use: who, from which IP, how many requests, last seen. Most recent first.")
    @GetMapping("/sessions")
    public ResponseEntity<ApiResponse<List<SessionResponse>>> getSessions() {
        List<SessionResponse> list = sessions.sessions().stream().map(SessionResponse::from).toList();
        return ApiResponse.ok("Active sessions retrieved successfully", list);
    }

    @Operation(summary = "Log a user out everywhere",
            description = "Revokes every token of the user (a stolen token, a suspicious account). "
                    + "Returns how many tokens were revoked.")
    @DeleteMapping("/users/{id}/sessions")
    public ResponseEntity<ApiResponse<Integer>> revokeSessions(@PathVariable @Positive Long id) {
        return ApiResponse.ok("Sessions revoked successfully", sessions.revokeAll(id));
    }
}
