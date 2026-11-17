package com.blogPlatform.III.blog.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

// no format rules here (unlike sign-up): they would tell a guesser what a valid username looks like
public record LoginRequest(
        @Schema(example = "serge") @NotBlank @Size(max = 100) String username,
        @Schema(example = "secret123") @NotBlank @Size(max = 100) String password
) {
}
