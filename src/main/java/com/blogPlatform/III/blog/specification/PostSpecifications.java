package com.blogPlatform.III.blog.specification;

import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.entity.Tag;
import jakarta.persistence.criteria.Join;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDate;

public class PostSpecifications {

    public static Specification<Post> hasStatus(PostStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    public static Specification<Post> hasAuthor(String username) {
        return (root, query, cb) -> cb.equal(root.get("author").get("username"), username);
    }

    public static Specification<Post> hasTag(String tagName) {
        return (root, query, cb) -> {
            Join<Post, Tag> tags = root.join("tags");
            return cb.equal(tags.get("name"), tagName);
        };
    }

    public static Specification<Post> matchesKeyword(String keyword) {
        // match_against is our own Hibernate function (util/MySqlFunctionContributor)
        return (root, query, cb) -> cb.greaterThan(
                cb.function("match_against", Double.class,
                        root.get("title"), root.get("content"), cb.literal(keyword)),
                0.0);
    }

    public static Specification<Post> createdFrom(LocalDate from) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("createdAt"), from.atStartOfDay());
    }

    public static Specification<Post> createdTo(LocalDate to) {
        return (root, query, cb) -> cb.lessThan(root.get("createdAt"), to.plusDays(1).atStartOfDay());
    }
}
