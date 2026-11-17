package com.blogPlatform.III.blog.repository;

import com.blogPlatform.III.blog.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    @EntityGraph(attributePaths = "user")
    Page<Comment> findByPostId(Long postId, Pageable pageable);

    long countByPostId(Long postId);

    Optional<Comment> findByIdAndPostId(Long id, Long postId);

    @Query("SELECT c.user.id FROM Comment c WHERE c.id = :id AND c.post.id = :postId")
    Optional<Long> findUserIdByIdAndPostId(@Param("id") Long id, @Param("postId") Long postId);

    // soft delete of many rows in one statement. An UPDATE on purpose: a JPQL "DELETE FROM Comment"
    // would skip @SQLDelete and really remove the rows.

    @Modifying
    @Query("UPDATE Comment c SET c.deletedAt = CURRENT_TIMESTAMP WHERE c.post.id = :postId AND c.deletedAt IS NULL")
    int softDeleteByPost(@Param("postId") Long postId);
}
