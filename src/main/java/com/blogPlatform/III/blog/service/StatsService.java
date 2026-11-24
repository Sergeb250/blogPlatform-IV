package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.dto.response.StatsResponse;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class StatsService {

    private final UserRepository userRepository;
    private final PostRepository postRepository;
    private final CommentRepository commentRepository;
    private final ReviewRepository reviewRepository;
    private final TagRepository tagRepository;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public StatsResponse getStats() {
        long posts = postRepository.count();
        long published = postRepository.countByStatus(PostStatus.PUBLISHED);
        long drafts = postRepository.countByStatus(PostStatus.DRAFT);
        Double average = reviewRepository.findAverageRating();

        return new StatsResponse(
                userRepository.count(),
                posts,
                published,
                drafts,
                commentRepository.count(),
                reviewRepository.count(),
                tagRepository.count(),
                average == null ? 0.0 : Math.round(average * 100) / 100.0);
    }
}
