package com.blogPlatform.III.blog.repository;

import com.blogPlatform.III.blog.entity.Review;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    // the reviewer is JOINed in, like the comment list: no extra query per review
    @EntityGraph(attributePaths = "user")
    Page<Review> findByPostId(Long postId, Pageable pageable);

    boolean existsByPostIdAndUserId(Long postId, Long userId);

    Optional<Review> findByIdAndPostId(Long id, Long postId);

    @Query("SELECT r.user.id FROM Review r WHERE r.id = :id AND r.post.id = :postId")
    Optional<Long> findUserIdByIdAndPostId(@Param("id") Long id, @Param("postId") Long postId);

    @Query("SELECT AVG(r.rating) FROM Review r")
    Double findAverageRating();

    // soft delete of many rows in one statement. An UPDATE on purpose: a JPQL "DELETE FROM Review"
    // would skip @SQLDelete and really remove the rows.

    @Modifying
    @Query("UPDATE Review r SET r.deletedAt = CURRENT_TIMESTAMP WHERE r.post.id = :postId AND r.deletedAt IS NULL")
    int softDeleteByPost(@Param("postId") Long postId);
}
