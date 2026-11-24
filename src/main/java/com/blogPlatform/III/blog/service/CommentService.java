package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.CommentRequest;
import com.blogPlatform.III.blog.dto.request.CommentUpdateRequest;
import com.blogPlatform.III.blog.dto.response.CommentResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.entity.Comment;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.CommentRepository;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentService {

    private final CommentRepository commentRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true)
    @Transactional
    public CommentResponse add(Long postId, Long userId, CommentRequest request) {
        Post post = postRepository.findOrThrow(postId);
        User user = userRepository.findOrThrow(userId);

        Comment comment = new Comment();
        comment.setPost(post);
        comment.setUser(user);
        comment.setContent(request.content());
        return CommentResponse.from(commentRepository.save(comment));
    }

    public PageResponse<CommentResponse> getByPost(Long postId, Pageable pageable) {
        postRepository.requireExists(postId);

        Page<Comment> page = commentRepository.findByPostId(postId, pageable);
        return PageResponse.from(page.map(CommentResponse::from));
    }

    public long countByPost(Long postId) {
        postRepository.requireExists(postId);
        return commentRepository.countByPostId(postId);
    }

    @Transactional
    public CommentResponse update(Long postId, Long commentId, CommentUpdateRequest request) {
        Comment comment = findComment(postId, commentId);

        if (!comment.getVersion().equals(request.version())) {
            throw new ObjectOptimisticLockingFailureException(Comment.class, commentId);
        }

        comment.setContent(request.content());
        return CommentResponse.from(commentRepository.saveAndFlush(comment));
    }

    @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true)
    @Transactional
    public void delete(Long postId, Long commentId) {
        Comment comment = findComment(postId, commentId);
        commentRepository.delete(comment);
    }

    private Comment findComment(Long postId, Long commentId) {
        return commentRepository.findByIdAndPostId(commentId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Comment " + commentId + " not found on post " + postId));
    }
}
