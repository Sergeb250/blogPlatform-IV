package com.blogPlatform.III.blog.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;
import org.hibernate.annotations.SQLDelete;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.jdbc.Expectation;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.Set;

// soft delete: remove() runs this UPDATE instead of a DELETE, and every query skips rows with deleted_at set.
// verify = RowCount: 0 rows updated (deleted at the same moment by someone else) is an error, not a silent success.
// The post_tags links stay; a deleted tag simply no longer shows up on its posts.
// The name is unique among live tags only: see util/SoftDeleteSchemaInitializer
@Entity
@Table(name = "tags")
@SQLDelete(sql = "UPDATE tags SET deleted_at = NOW(6) WHERE tag_id = ? AND deleted_at IS NULL",
        verify = Expectation.RowCount.class)
@SQLRestriction("deleted_at IS NULL")
@Getter
@Setter
@NoArgsConstructor
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "tag_id")
    private Long id;

    @Column(name = "name", nullable = false, length = 50)
    private String name;

    // NULL = live; set when the tag is deleted
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @ManyToMany(mappedBy = "tags")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private Set<Post> posts = new HashSet<>();
}
