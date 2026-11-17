package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.request.CommentRequest;
import com.blogPlatform.III.blog.dto.request.CommentUpdateRequest;
import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.CommentResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.service.CommentService;
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
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Comments", description = "Comments of one post")
@RestController
@RequestMapping("/api/posts/{postId}/comments")
@RequiredArgsConstructor
public class CommentController {

    private final CommentService commentService;

    @Operation(summary = "Add a comment (any logged-in user)",
            description = "The comment is written by the logged-in user (from the token). "
                    + "400: invalid body. 404: post not found.")
    @PreAuthorize("isAuthenticated()")
    @PostMapping
    public ResponseEntity<ApiResponse<CommentResponse>> add(@PathVariable @Positive Long postId,
                                                            @AuthenticationPrincipal AuthUser me,
                                                            @Valid @RequestBody CommentRequest request) {
        return ApiResponse.created("Comment added successfully", commentService.add(postId, me.id(), request));
    }

    @Operation(summary = "List the comments of a post",
            description = "Default: page 0, 10 items, oldest first. size: 1-100. Sort fields: id, createdAt. "
                    + "404: post not found.")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<CommentResponse>>> getByPost(
            @PathVariable @Positive Long postId,
            @ParameterObject
            @ValidPageable(sortFields = {"id", "createdAt"})
            @PageableDefault(size = 10, sort = "createdAt", direction = Sort.Direction.ASC) Pageable pageable) {
        return ApiResponse.ok("Comments retrieved successfully", commentService.getByPost(postId, pageable));
    }

    @Operation(summary = "Count the comments of a post", description = "404: post not found.")
    @GetMapping("/count")
    public ResponseEntity<ApiResponse<Long>> count(@PathVariable @Positive Long postId) {
        return ApiResponse.ok("Comment count retrieved successfully", commentService.countByPost(postId));
    }

    @Operation(summary = "Edit a comment (its writer, ADMIN)",
            description = "Send the version you read (optimistic locking). 403: not your comment. "
                    + "404: comment not found on this post. 409: someone changed the comment since you read it.")
    @PreAuthorize("hasRole('ADMIN') or @ownership.isCommentAuthor(#postId, #commentId, authentication)")
    @PutMapping("/{commentId}")
    public ResponseEntity<ApiResponse<CommentResponse>> update(@PathVariable @Positive Long postId,
                                                               @PathVariable @Positive Long commentId,
                                                               @Valid @RequestBody CommentUpdateRequest request) {
        return ApiResponse.ok("Comment updated successfully", commentService.update(postId, commentId, request));
    }

    @Operation(summary = "Delete a comment (its writer, ADMIN; soft delete)",
            description = "The row is kept and marked with deleted_at; the API never shows it again. "
                    + "403: not your comment. 404: comment not found on this post, or already deleted.")
    @PreAuthorize("hasRole('ADMIN') or @ownership.isCommentAuthor(#postId, #commentId, authentication)")
    @DeleteMapping("/{commentId}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable @Positive Long postId,
                                                    @PathVariable @Positive Long commentId) {
        commentService.delete(postId, commentId);
        return ApiResponse.ok("Comment deleted successfully", null);
    }
}
