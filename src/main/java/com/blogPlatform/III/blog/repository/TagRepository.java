package com.blogPlatform.III.blog.repository;

import com.blogPlatform.III.blog.dto.response.TagUsageView;
import com.blogPlatform.III.blog.entity.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Optional;

public interface TagRepository extends JpaRepository<Tag, Long> {

    Optional<Tag> findByName(String name);

    boolean existsByName(String name);

    // native SQL: @SQLRestriction is not added, so deleted tags are left out and only live posts are counted by hand.
    @Query(value = """
            SELECT t.tag_id AS tagId, t.name AS name, COUNT(p.post_id) AS postCount
            FROM tags t
            LEFT JOIN post_tags pt ON pt.tag_id = t.tag_id
            LEFT JOIN posts p ON p.post_id = pt.post_id AND p.deleted_at IS NULL
            WHERE t.deleted_at IS NULL
            GROUP BY t.tag_id, t.name
            ORDER BY postCount DESC, t.name
            """,
            countQuery = "SELECT COUNT(*) FROM tags WHERE deleted_at IS NULL",
            nativeQuery = true)
    Page<TagUsageView> findTagUsage(Pageable pageable);
}
