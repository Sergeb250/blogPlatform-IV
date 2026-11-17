package com.blogPlatform.III.blog.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CommentRequest(
        @Schema(example = "Great post, thanks!") @NotBlank @Size(max = 2000) String content
) {
}
