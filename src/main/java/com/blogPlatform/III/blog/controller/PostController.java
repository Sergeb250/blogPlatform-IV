package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.*;
import com.blogPlatform.III.blog.dto.response.*;
import com.blogPlatform.III.blog.exception.ContentRejectedException;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.service.PostService;
import com.blogPlatform.III.blog.validation.ValidCursor;
import com.blogPlatform.III.blog.validation.ValidPageable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Posts", description = "Create, read, filter, search, update and delete blog posts")
@RestController
@RequestMapping("/api/posts")
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    @Operation(summary = "Create a post (AUTHOR, ADMIN)",
            description = "The author is the logged-in user (from the token). "
                    + "Missing tags are created (names are saved in lowercase). Status is DRAFT if not given. "
                    + "400: invalid body or banned word in the content. 403: a READER can't write posts. "
                    + "409: you already have a post with this title.")
    @PreAuthorize("hasAnyRole('AUTHOR', 'ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<PostResponse>> create(@AuthenticationPrincipal AuthUser me,
                                                            @Valid @RequestBody PostRequest request)
            throws ContentRejectedException {
        return ApiResponse.created("Post created successfully", postService.create(me.id(), request));
    }

    @Operation(summary = "List posts (filters + paging + sorting)",
            description = "All filters are optional and can be combined (JPA Specifications). "
                    + "Default: page 0, 10 items, newest first. size: 1-100. "
                    + "Sort fields: id, title, status, createdAt, updatedAt. 400: invalid filter, size or sort. "
                    + "Example: ?status=PUBLISHED&tag=java&keyword=docker&page=0&size=20&sort=title,asc")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<PostResponse>>> getPosts(
            @Valid @ParameterObject PostFilter filter,
            @ParameterObject
            @ValidPageable(sortFields = {"id", "title", "status", "createdAt", "updatedAt"})
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("Posts retrieved successfully", postService.search(filter, pageable));
    }

    @Operation(summary = "Feed with cursor (keyset) paging",
            description = "Newest posts first. Send the nextCursor of the last response to get the next window. "
                    + "Every window costs the same, also deep in the list (no OFFSET). 400: invalid cursor or size.")
    @GetMapping("/feed")
    public ResponseEntity<ApiResponse<CursorPageResponse<PostResponse>>> getFeed(
            @Parameter(description = "nextCursor from the previous response; empty = start at the newest post")
            @RequestParam(required = false)
            @ValidCursor
            String cursor,
            @Parameter(description = "posts per window (1-100)", example = "20")
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int size) {
        return ApiResponse.ok("Feed retrieved successfully", postService.feed(cursor, size));
    }

    @Operation(summary = "Full-text search",
            description = "MySQL FULLTEXT search in title and content, best match first. Matches whole words "
                    + "(3+ letters). Results are ordered by relevance, so ?sort= is not allowed (400).")
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<PageResponse<PostResponse>>> searchByKeyword(
            @Parameter(description = "word to search for, 3-100 characters", example = "docker", required = true)
            @RequestParam @NotBlank @Size(min = 3, max = 100) String keyword,
            @ParameterObject @ValidPageable(sortFields = {}) @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.ok("Search completed successfully", postService.searchByKeyword(keyword, pageable));
    }

    @Operation(summary = "Top rated posts (paged)",
            description = "Posts with an average rating >= minRating (1-5), best first (JPQL + DTO projection). "
                    + "Default: page 0, 10 posts. size: 1-100. The ranking order is fixed, so ?sort= is not allowed (400). "
                    + "Cached 60 s per page.")
    @GetMapping("/top-rated")
    public ResponseEntity<ApiResponse<PageResponse<PostRatingDto>>> getTopRated(
            @Parameter(description = "minimum average rating (1-5)", example = "4.5")
            @RequestParam(defaultValue = "4") @DecimalMin("1.0") @DecimalMax("5.0") double minRating,
            @ParameterObject @ValidPageable(sortFields = {}) @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.ok("Top rated posts retrieved successfully", postService.getTopRated(minRating, pageable));
    }

    @Operation(summary = "Post summaries (light list)",
            description = "id, title, author, status and comment count only (no content, no tags). "
                    + "Default: 20 per page, newest first. size: 1-100. Sort fields: id, title, createdAt.")
    @GetMapping("/summaries")
    public ResponseEntity<ApiResponse<PageResponse<PostSummaryDto>>> getSummaries(
            @ParameterObject
            @ValidPageable(sortFields = {"id", "title", "createdAt"})
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("Post summaries retrieved successfully", postService.getSummaries(pageable));
    }

    @Operation(summary = "Popular posts (paged)",
            description = "Published posts ranked by comments + reviews + average rating (native SQL). "
                    + "Default: page 0, 10 posts. size: 1-100. The ranking order is fixed, so ?sort= is not allowed (400). "
                    + "Cached 60 s per page, refreshed after every new comment or review.")
    @GetMapping("/popular")
    public ResponseEntity<ApiResponse<PageResponse<PopularPostResponse>>> getPopular(
            @ParameterObject @ValidPageable(sortFields = {}) @PageableDefault(size = 10) Pageable pageable) {
        return ApiResponse.ok("Popular posts retrieved successfully", postService.getPopular(pageable));
    }

    @Operation(summary = "Get one post", description = "Cached. 400: id is not a positive number. 404: post not found.")
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<PostResponse>> getById(@PathVariable @Positive Long id) {
        return ApiResponse.ok("Post retrieved successfully", postService.getById(id));
    }

    @Operation(summary = "Update a post (its AUTHOR, ADMIN)",
            description = "Send the version you read (optimistic locking). Tags/status are kept if not sent. "
                    + "400: invalid body, missing version or banned word. 403: not your post. 404: not found. "
                    + "409: someone changed the post since you read it (old version), or duplicate title.")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('AUTHOR') and @ownership.isPostAuthor(#id, authentication))")
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<PostResponse>> update(@PathVariable @Positive Long id,
                                                            @Valid @RequestBody PostUpdateRequest request)
            throws ContentRejectedException {
        return ApiResponse.ok("Post updated successfully", postService.update(id, request));
    }

    @Operation(summary = "Publish a post (its AUTHOR, ADMIN)",
            description = "Sets status = PUBLISHED in one SQL UPDATE. 403: not your post. 404: not found.")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('AUTHOR') and @ownership.isPostAuthor(#id, authentication))")
    @PatchMapping("/{id}/publish")
    public ResponseEntity<ApiResponse<PostResponse>> publish(@PathVariable @Positive Long id) {
        return ApiResponse.ok("Post published successfully", postService.publish(id));
    }

    @Operation(summary = "Delete a post (its AUTHOR, ADMIN; soft delete)",
            description = "The row is kept and marked with deleted_at; the API never shows it again. Its comments and "
                    + "reviews are soft-deleted too. The title can be used again. 403: not your post. "
                    + "404: not found or already deleted.")
    @PreAuthorize("hasRole('ADMIN') or (hasRole('AUTHOR') and @ownership.isPostAuthor(#id, authentication))")
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long id) {
        postService.delete(id);
        return ApiResponse.ok("Post deleted successfully", null);
    }
}
