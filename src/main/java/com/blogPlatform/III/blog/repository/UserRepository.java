package com.blogPlatform.III.blog.repository;

import com.blogPlatform.III.blog.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    // User has no @SQLRestriction, so every live-user lookup filters deleted_at here.
    // Associations (Post.author, Comment.user, Review.user) still load a soft-deleted account.

    @Override
    @Query("SELECT u FROM User u WHERE u.id = :id AND u.deletedAt IS NULL")
    Optional<User> findById(@Param("id") Long id);

    @Override
    @Query(value = "SELECT u FROM User u WHERE u.deletedAt IS NULL",
            countQuery = "SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL")
    Page<User> findAll(Pageable pageable);

    @Override
    @Query("SELECT COUNT(u) FROM User u WHERE u.deletedAt IS NULL")
    long count();

    // roles in the same query: login reads them after this call, outside any transaction (LAZY would fail there)
    @EntityGraph(attributePaths = "roles")
    @Query("SELECT u FROM User u WHERE u.username = :username AND u.deletedAt IS NULL")
    Optional<User> findByUsername(@Param("username") String username);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.username = :username AND u.deletedAt IS NULL")
    boolean existsByUsername(@Param("username") String username);

    @Query("SELECT COUNT(u) > 0 FROM User u WHERE u.email = :email AND u.deletedAt IS NULL")
    boolean existsByEmail(@Param("email") String email);
}
