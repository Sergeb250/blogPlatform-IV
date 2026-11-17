package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.validation.Username;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UserRequest(

        @Schema(example = "serge", description = "3-50 characters: letters, digits, . _ -") @NotBlank @Username String username,

        @Schema(example = "serge@blog.com")
        @NotBlank @Size(max = 100) @Email(regexp = "^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$", message = "must be a valid email address")
        String email,

        @Schema(example = "secret123")
        @NotBlank @Size(min = 8, max = 100)
        @Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "must contain at least one letter and one digit")
        String password
) {
}
