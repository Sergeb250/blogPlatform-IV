package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.TagRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.TagResponse;
import com.blogPlatform.III.blog.dto.response.TagUsageView;
import com.blogPlatform.III.blog.service.TagService;
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
import org.springframework.web.bind.annotation.*;

@Tag(name = "Tags", description = "Tags that label posts")
@RestController
@RequestMapping("/api/tags")
@RequiredArgsConstructor
public class TagController {

    private final TagService tagService;

    @Operation(summary = "Create a tag (AUTHOR, ADMIN)",
            description = "The name is saved in lowercase. 400: invalid body. 403: a READER can't create tags. "
                    + "409: tag already exists.")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<TagResponse>> create(@Valid @RequestBody TagRequest request) {
        return ApiResponse.created("Tag created successfully", tagService.create(request));
    }

    @Operation(summary = "List tags (paged)",
            description = "Default: page 0, 20 tags, A → Z. size: 1-100. Sort fields: id, name. "
                    + "Cached (1 h) per page, refreshed when a tag is created or deleted. 400: invalid size or sort.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<TagResponse>>> getAll(
            @ParameterObject
            @ValidPageable(sortFields = {"id", "name"})
            @PageableDefault(size = 20, sort = "name", direction = Sort.Direction.ASC) Pageable pageable) {
        return ApiResponse.ok("Tags retrieved successfully", tagService.getAll(pageable));
    }

    @Operation(summary = "Tag usage (paged)",
            description = "How many live posts use each tag (native SQL), most used first. Default: page 0, 20 tags. "
                    + "size: 1-100. The order is fixed, so ?sort= is not allowed (400).")
    @GetMapping("/usage")
    public ResponseEntity<ApiResponse<PageResponse<TagUsageView>>> getUsage(
            @ParameterObject @ValidPageable(sortFields = {}) @PageableDefault(size = 20) Pageable pageable) {
        return ApiResponse.ok("Tag usage retrieved successfully", tagService.getUsage(pageable));
    }

    @Operation(summary = "Get one tag", description = "404: tag not found.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<TagResponse>> getById(@PathVariable @Positive Long id) {
        return ApiResponse.ok("Tag retrieved successfully", tagService.getById(id));
    }

    @Operation(summary = "Delete a tag (ADMIN; soft delete)",
            description = "The row is kept and marked with deleted_at; the tag no longer shows on any post. "
                    + "The name can be used again. 403: not an admin. 404: not found or already deleted.")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long id) {
        tagService.delete(id);
        return ApiResponse.ok("Tag deleted successfully", null);
    }
}
