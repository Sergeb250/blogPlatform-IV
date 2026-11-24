package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.ReviewRequest;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.ReviewResponse;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.Review;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.DuplicateResourceException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.repository.ReviewRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReviewService {

    private final ReviewRepository reviewRepository;
    private final PostRepository postRepository;
    private final UserRepository userRepository;

    @CacheEvict(cacheNames = {CacheConfig.POPULAR_POSTS, CacheConfig.TOP_RATED_POSTS}, allEntries = true)
    @Transactional
    public ReviewResponse add(Long postId, Long userId, ReviewRequest request) {
        Post post = postRepository.findOrThrow(postId);
        User user = userRepository.findOrThrow(userId);

        if (reviewRepository.existsByPostIdAndUserId(postId, user.getId())) {
            throw new DuplicateResourceException("User " + user.getId() + " already reviewed post " + postId);
        }

        Review review = new Review();
        review.setPost(post);
        review.setUser(user);
        review.setRating(request.rating());
        review.setReviewText(request.reviewText());
        return ReviewResponse.from(reviewRepository.save(review));
    }

    public PageResponse<ReviewResponse> getByPost(Long postId, Pageable pageable) {
        postRepository.requireExists(postId);
        return PageResponse.from(reviewRepository.findByPostId(postId, pageable).map(ReviewResponse::from));
    }

    @CacheEvict(cacheNames = {CacheConfig.POPULAR_POSTS, CacheConfig.TOP_RATED_POSTS}, allEntries = true)
    @Transactional
    public void delete(Long postId, Long reviewId) {
        Review review = reviewRepository.findByIdAndPostId(reviewId, postId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Review " + reviewId + " not found on post " + postId));
        reviewRepository.delete(review);
    }
}
