package com.blogPlatform.III.blog.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record ReviewRequest(
        @Schema(example = "5") @NotNull @Min(1) @Max(5) Integer rating,
        @Schema(example = "Very helpful", description = "optional") @Size(max = 2000) String reviewText
) {
}
