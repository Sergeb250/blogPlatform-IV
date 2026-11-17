package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.CommentRepository;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.repository.ReviewRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// "Is the caller the owner?" checks for @PreAuthorize, e.g. @ownership.isPostAuthor(#id, authentication).
// A missing post / comment / review throws 404 here, so a caller learns "not found" before "not yours".
@Component("ownership")
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class Ownership {

    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ReviewRepository reviewRepository;

    public boolean isPostAuthor(Long postId, Authentication authentication) {
        Long authorId = postRepository.findAuthorIdById(postId)
                .orElseThrow(() -> new ResourceNotFoundException("Post " + postId + " not found"));
        return authorId.equals(userId(authentication));
    }

    public boolean isCommentAuthor(Long postId, Long commentId, Authentication authentication) {
        Long authorId = commentRepository.findUserIdByIdAndPostId(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Comment " + commentId + " not found on post " + postId));
        return authorId.equals(userId(authentication));
    }

    public boolean isReviewAuthor(Long postId, Long reviewId, Authentication authentication) {
        Long authorId = reviewRepository.findUserIdByIdAndPostId(reviewId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Review " + reviewId + " not found on post " + postId));
        return authorId.equals(userId(authentication));
    }

    public boolean isSelf(Long userId, Authentication authentication) {
        return userId.equals(userId(authentication));
    }

    // the id comes from the token's "uid" claim (AuthUser), never from the request body
    private static Long userId(Authentication authentication) {
        return authentication != null && authentication.getPrincipal() instanceof AuthUser user ? user.id() : null;
    }
}
