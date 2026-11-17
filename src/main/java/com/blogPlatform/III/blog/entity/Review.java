package com.blogPlatform.III.blog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.jdbc.Expectation;

import java.time.LocalDateTime;

// soft delete: remove() runs this UPDATE instead of a DELETE, and every query skips rows with deleted_at set.
// verify = RowCount: 0 rows updated (deleted at the same moment by someone else) is an error, not a silent success.
// one review per user per post, counting live reviews only: see util/SoftDeleteSchemaInitializer
@Entity
// covering index for "live reviews and their average per post" (popular, top rated): no row has to be read
@Table(name = "reviews",
        indexes = @Index(name = "idx_reviews_post_live_rating", columnList = "post_id, deleted_at, rating"))
@SQLDelete(sql = "UPDATE reviews SET deleted_at = NOW(6) WHERE review_id = ? AND deleted_at IS NULL",
        verify = Expectation.RowCount.class)
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "review_id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "post_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Post post;

    // the same column again, read-only, as a plain number: "r.postId" in a query needs no JOIN to posts
    // ("r.post.id" does, because of the soft-delete restriction on Post). Used by findTopRated.
    @Column(name = "post_id", insertable = false, updatable = false)
    private Long postId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "rating", nullable = false)
    private Integer rating;

    @Column(name = "review_text", columnDefinition = "TEXT")
    private String reviewText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // NULL = live; set when the review is deleted
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
