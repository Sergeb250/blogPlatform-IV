package com.blogPlatform.III.blog.repository;

import com.blogPlatform.III.blog.dto.response.PopularPostView;
import com.blogPlatform.III.blog.dto.response.PostRatingDto;
import com.blogPlatform.III.blog.dto.response.PostSummaryDto;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PostRepository extends JpaRepository<Post, Long>, JpaSpecificationExecutor<Post> {

    long countByStatus(PostStatus status);

    boolean existsByAuthorIdAndTitle(Long authorId, String title);

    @Query("SELECT p.author.id FROM Post p WHERE p.id = :id")
    Optional<Long> findAuthorIdById(@Param("id") Long id);

    // native SQL: @SQLRestriction is not added here, so "deleted_at IS NULL" is written by hand
    @Query(value = """
            SELECT * FROM posts
            WHERE MATCH(title, content) AGAINST (:keyword IN NATURAL LANGUAGE MODE)
              AND deleted_at IS NULL
            """,
            countQuery = """
            SELECT COUNT(*) FROM posts
            WHERE MATCH(title, content) AGAINST (:keyword IN NATURAL LANGUAGE MODE)
              AND deleted_at IS NULL
            """,
            nativeQuery = true)
    Page<Post> searchFullText(@Param("keyword") String keyword, Pageable pageable);

    // reviews are averaged first (covering index), then joined to posts: ~0.2 s instead of ~1.7 s
    // a GROUP BY query can't be counted by Spring Data, so the count query is written out
    @Query(value = """
            SELECT new com.blogPlatform.III.blog.dto.response.PostRatingDto(p.id, p.title, t.averageRating, t.reviewCount)
            FROM (SELECT r.postId AS postId, AVG(r.rating) AS averageRating, COUNT(r) AS reviewCount
                  FROM Review r
                  GROUP BY r.postId
                  HAVING AVG(r.rating) >= :minRating) t
            JOIN Post p ON p.id = t.postId
            ORDER BY t.averageRating DESC, p.id DESC
            """,
            countQuery = """
            SELECT COUNT(p) FROM Post p
            WHERE p.id IN (SELECT r.postId FROM Review r GROUP BY r.postId HAVING AVG(r.rating) >= :minRating)
            """)
    Page<PostRatingDto> findTopRated(@Param("minRating") double minRating, Pageable pageable);

    @Query(value = """
            SELECT new com.blogPlatform.III.blog.dto.response.PostSummaryDto(
                p.id, p.title, a.username, p.status,
                (SELECT COUNT(c) FROM Comment c WHERE c.post = p))
            FROM Post p JOIN p.author a
            """,
            countQuery = "SELECT COUNT(p) FROM Post p")
    Page<PostSummaryDto> findSummaries(Pageable pageable);

    // a bulk UPDATE skips @Version and @UpdateTimestamp, so both are set here
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Post p SET p.status = :status, p.updatedAt = :now, p.version = p.version + 1 WHERE p.id = :id")
    int updateStatus(@Param("id") Long id,
                     @Param("status") PostStatus status,
                     @Param("now") LocalDateTime now);

    @Query(value = """
            SELECT p.post_id                          AS postId,
                   p.title                            AS title,
                   COALESCE(c.comment_count, 0)       AS commentCount,
                   COALESCE(r.review_count, 0)        AS reviewCount,
                   COALESCE(r.average_rating, 0)      AS averageRating,
                   COALESCE(c.comment_count, 0) + COALESCE(r.review_count, 0)
                       + COALESCE(r.average_rating, 0) AS score
            FROM posts p
            LEFT JOIN (SELECT post_id, COUNT(*) AS comment_count
                       FROM comments WHERE deleted_at IS NULL GROUP BY post_id) c ON c.post_id = p.post_id
            LEFT JOIN (SELECT post_id, COUNT(*) AS review_count, AVG(rating) AS average_rating
                       FROM reviews WHERE deleted_at IS NULL GROUP BY post_id) r ON r.post_id = p.post_id
            WHERE p.status = 'PUBLISHED' AND p.deleted_at IS NULL
            ORDER BY score DESC, p.post_id DESC
            """,
            countQuery = "SELECT COUNT(*) FROM posts WHERE status = 'PUBLISHED' AND deleted_at IS NULL",
            nativeQuery = true)
    Page<PopularPostView> findPopular(Pageable pageable);

    default Post findOrThrow(Long id) {
        return findById(id).orElseThrow(() -> new ResourceNotFoundException("Post " + id + " not found"));
    }

    default void requireExists(Long id) {
        if (!existsById(id)) {
            throw new ResourceNotFoundException("Post " + id + " not found");
        }
    }
}
