package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.entity.RoleName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.Set;

public record RoleUpdateRequest(
        @Schema(example = "[\"AUTHOR\", \"READER\"]", description = "the user's new, complete list of roles: ADMIN, AUTHOR, READER")
        @NotEmpty(message = "a user needs at least one role")
        Set<@NotNull RoleName> roles
) {
}
