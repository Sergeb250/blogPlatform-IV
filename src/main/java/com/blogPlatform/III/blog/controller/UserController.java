package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.UserResponse;
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
import org.springframework.web.bind.annotation.*;

@Tag(name = "Users", description = "Blog users (authors and readers)")
@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @Operation(summary = "List users (paged)",
            description = "Default: page 0, 20 users, A → Z by username. size: 1-100. "
                    + "Sort fields: id, username, createdAt. 400: invalid size or sort.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<UserResponse>>> getAll(
            @ParameterObject
            @ValidPageable(sortFields = {"id", "username", "createdAt"})
            @PageableDefault(size = 20, sort = "username", direction = Sort.Direction.ASC) Pageable pageable) {
        return ApiResponse.ok("Users retrieved successfully", userService.getAll(pageable));
    }

    @Operation(summary = "Get one user", description = "Cached. 400: id is not a positive number. 404: user not found.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> getById(@PathVariable @Positive Long id) {
        return ApiResponse.ok("User retrieved successfully", userService.getById(id));
    }

    @Operation(summary = "Find a user by username", description = "400: not a valid username. 404: user not found.")
    @GetMapping("/by-username/{username}")
    public ResponseEntity<ApiResponse<UserResponse>> getByUsername(@PathVariable @Username String username) {
        return ApiResponse.ok("User retrieved successfully", userService.getByUsername(username));
    }

    @Operation(summary = "Update a user",
            description = "Same rules as sign-up (POST /api/auth/register); the roles stay as they are. "
                    + "400: invalid body. 404: user not found. 409: username or email already used.")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<UserResponse>> update(@PathVariable @Positive Long id,
                                                            @Valid @RequestBody UserRequest request) {
        return ApiResponse.ok("User updated successfully", userService.update(id, request));
    }

    @Operation(summary = "Delete a user (soft delete)",
            description = "The row is kept and marked with deleted_at; the API never shows that user again. "
                    + "Their posts, comments and reviews stay live and still name them. "
                    + "The username and email can be used again. 404: not found or already deleted.")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long id) {
        userService.delete(id);
        return ApiResponse.ok("User deleted successfully", null);
    }
}
