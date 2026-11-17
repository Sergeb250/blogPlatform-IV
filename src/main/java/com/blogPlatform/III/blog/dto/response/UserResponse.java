package com.blogPlatform.III.blog.dto.response;

import com.blogPlatform.III.blog.entity.AuthProvider;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;

import java.time.LocalDateTime;
import java.util.Set;

public record UserResponse(Long id, String username, String email, Set<RoleName> roles,
                           AuthProvider authProvider, LocalDateTime createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getUsername(), user.getEmail(),
                user.roleNames(), user.getAuthProvider(), user.getCreatedAt());
    }
}
