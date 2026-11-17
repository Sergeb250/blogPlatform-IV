package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.RoleUpdateRequest;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.service.UserService;
import com.blogPlatform.III.blog.validation.Username;
import com.blogPlatform.III.blog.validation.ValidPageable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "Blog users (authors and readers)")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users (ADMIN, paged)",
            description = "Default: page 0, 20 users, A → Z by username. size: 1-100. "
                    + "Sort fields: id, username, createdAt. 400: invalid size or sort. 403: not an admin.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAll(
            @ParameterObject
            @ValidPageable(sortFields = {"id", "username", "createdAt"})
            @PageableDefault(size = 20, sort = "username", direction = Sort.Direction.ASC) Pageable pageable) {
        return ApiResponse.ok("Users retrieved successfully", userService.getAll(pageable));
    }

    @Operation(summary = "Get one user (yourself, ADMIN)",
            description = "Cached. 400: id is not a positive number. 403: not your account. 404: user not found.")
    @PreAuthorize("hasRole('ADMIN') or @ownership.isSelf(#id, authentication)")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable @Positive Long id) {
        return ApiResponse.ok("User retrieved successfully", userService.getById(id));
    }

    @Operation(summary = "Find a user by username (yourself, ADMIN)",
            description = "400: not a valid username. 403: not your account. 404: user not found.")
    @PreAuthorize("hasRole('ADMIN') or #username == authentication.name")
    @GetMapping("/by-username/{username}")
    public ResponseEntity<ApiResponse<UserResponse>> getByUsername(@PathVariable @Username String username) {
        return ApiResponse.ok("User retrieved successfully", userService.getByUsername(username));
    }

    @Operation(summary = "Update a user (yourself, ADMIN)",
            description = "Same rules as sign-up (POST /api/auth/register); the roles stay as they are. "
                    + "400: invalid body. 403: not your account. 404: user not found. 409: username or email already used.")
    @PreAuthorize("hasRole('ADMIN') or @ownership.isSelf(#id, authentication)")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> update(@PathVariable @Positive Long id,
                                                            @Valid @RequestBody UserRequest request) {
        return ApiResponse.ok("User updated successfully", userService.update(id, request));
    }

    @Operation(summary = "Change the roles of a user (ADMIN)",
            description = "Replaces all roles, e.g. {\"roles\": [\"AUTHOR\", \"READER\"]}. The user gets the new roles "
                    + "with their next login (a token keeps the roles it was signed with). 400: empty or unknown role. "
                    + "403: not an admin. 404: user not found. 409: an admin can't remove ADMIN from their own account.")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{id}/roles")
    public ResponseEntity<ApiResponse<UserResponse>> updateRoles(@PathVariable @Positive Long id,
                                                                 @AuthenticationPrincipal AuthUser me,
                                                                 @Valid @RequestBody RoleUpdateRequest request) {
        return ApiResponse.ok("Roles updated successfully", userService.updateRoles(id, request.roles(), me.id()));
    }

    @Operation(summary = "Delete a user (yourself, ADMIN; soft delete)",
            description = "The row is kept and marked with deleted_at; the API never shows that user again. "
                    + "Their posts, comments and reviews stay live and still name them. "
                    + "The username and email can be used again. 403: not your account. 404: not found or already deleted.")
    @PreAuthorize("hasRole('ADMIN') or @ownership.isSelf(#id, authentication)")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long id) {
        userService.delete(id);
        return ApiResponse.ok("User deleted successfully", null);
    }
}
