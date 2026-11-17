package com.blogPlatform.III.blog.dto.request;

import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.validation.TagName;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record PostRequest(
        @Schema(example = "Getting started with Spring Data JPA") @NotBlank @Size(min = 3, max = 200) String title,
        @Schema(example = "Repositories, queries and transactions explained.") @NotBlank @Size(max = 10_000) String content,
        @Schema(example = "PUBLISHED", description = "optional, DRAFT if missing") PostStatus status,

        @Schema(example = "[\"java\", \"spring\"]", description = "optional; max 10 tags, each 1-50 characters (letters, digits, spaces, . _ + # -); missing tags are created")
        @Size(max = 10, message = "a post can have at most 10 tags")
        List<@NotBlank @TagName String> tags
) {
}
