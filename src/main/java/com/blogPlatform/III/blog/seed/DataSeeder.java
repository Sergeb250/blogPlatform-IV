package com.blogPlatform.III.blog.seed;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Component
@Profile("seed")
@Order(1)
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    static final int USERS = 1_000;
    static final int AUTHORS = 200;
    static final int POSTS = 50_000;
    static final int COMMENTS = 200_000;
    static final int BUSY_POSTS = 10;
    static final int MAX_REVIEWS_PER_POST = 4;
    static final int BATCH_SIZE = 2_000;

    static final String[] TAGS = {
            "java", "spring", "jpa", "hibernate", "mysql", "postgresql", "docker", "kubernetes", "aws", "react",
            "angular", "vue", "typescript", "javascript", "python", "kotlin", "go", "rust", "git", "linux",
            "testing", "security", "caching", "performance", "microservices", "rest", "graphql", "devops", "ai", "career"};

    static final String[] SENTENCES = {
            "This guide walks through the basics step by step.",
            "We compare a few approaches and look at the trade-offs.",
            "Most teams run into this problem sooner or later.",
            "The example project is small, but the ideas scale well.",
            "Read the official documentation for every detail.",
            "Measure first, then decide what to optimize.",
            "Simple code is easier to test and to change.",
            "At the end there is a short checklist you can reuse."};

    static final String[] COMMENT_TEXTS = {
            "Great post, thanks!", "Very helpful.", "Can you write more about this?", "I had the same problem.",
            "Clear explanation.", "This saved me a lot of time.", "Interesting point of view.", "Nice examples."};

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    // fixed seed: the same data on every run, so before/after measurements compare fairly
    private final Random random = new Random(42);
    private final LocalDateTime firstDay = LocalDateTime.of(2024, 1, 1, 0, 0);

    @Override
    public void run(String... args) {
        Long existingUsers = jdbc.queryForObject("SELECT COUNT(*) FROM users", Long.class);
        if (existingUsers != null && existingUsers > 0) {
            log.info("Seeding skipped: the database already has data");
            return;
        }

        long start = System.currentTimeMillis();
        log.info("Seeding started ...");

        seedUsers();
        seedTags();
        LocalDateTime[] postDates = seedPosts();
        seedPostTags();
        seedComments(postDates);
        seedReviews(postDates);

        log.info("Seeding finished in {} s", (System.currentTimeMillis() - start) / 1000);
    }

    private void seedUsers() {
        // BCrypt is slow on purpose: hash once, all seeded users share the password
        String passwordHash = passwordEncoder.encode("password123");

        List<Object[]> rows = new ArrayList<>();
        for (int id = 1; id <= USERS; id++) {
            rows.add(new Object[]{id, "user" + id, "user" + id + "@blog.com", passwordHash, "LOCAL", randomDate()});
        }
        insert("users",
                "INSERT INTO users (user_id, username, email, password_hash, auth_provider, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows);

        // one statement for all 1,000 links: the first AUTHORS users write, the rest read
        int links = jdbc.update("""
                INSERT INTO user_roles (user_id, role_id)
                SELECT u.user_id, r.role_id FROM users u
                JOIN roles r ON r.name = IF(u.user_id <= ?, 'AUTHOR', 'READER')
                """, AUTHORS);
        log.info("  user_roles: {} rows", links);
    }

    private void seedTags() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < TAGS.length; i++) {
            rows.add(new Object[]{i + 1, TAGS[i]});
        }
        insert("tags", "INSERT INTO tags (tag_id, name) VALUES (?, ?)", rows);
    }

    private LocalDateTime[] seedPosts() {
        LocalDateTime[] dates = new LocalDateTime[POSTS + 1];
        List<Object[]> rows = new ArrayList<>();

        for (int id = 1; id <= POSTS; id++) {
            int authorId = 1 + random.nextInt(AUTHORS);
            String topic = randomTag();
            String title = "Post " + id + ": notes about " + topic;
            String status = random.nextInt(100) < 80 ? "PUBLISHED" : "DRAFT";
            LocalDateTime created = randomDate();
            dates[id] = created;

            rows.add(new Object[]{id, authorId, title, makeContent(topic), status, created, created, 0});
        }
        insert("posts",
                "INSERT INTO posts (post_id, author_id, title, content, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                rows);
        return dates;
    }

    private void seedPostTags() {
        List<Object[]> rows = new ArrayList<>();
        for (int postId = 1; postId <= POSTS; postId++) {
            Set<Integer> tagIds = new HashSet<>();
            int howMany = 1 + random.nextInt(3);
            while (tagIds.size() < howMany) {
                tagIds.add(1 + random.nextInt(TAGS.length));
            }
            for (int tagId : tagIds) {
                rows.add(new Object[]{postId, tagId});
            }
        }
        insert("post_tags", "INSERT INTO post_tags (post_id, tag_id) VALUES (?, ?)", rows);
    }

    private void seedComments(LocalDateTime[] postDates) {
        List<Object[]> rows = new ArrayList<>();
        for (int id = 1; id <= COMMENTS; id++) {
            int postId = random.nextInt(10) == 0
                    ? 1 + random.nextInt(BUSY_POSTS)
                    : 1 + random.nextInt(POSTS);
            int userId = 1 + random.nextInt(USERS);
            String text = COMMENT_TEXTS[random.nextInt(COMMENT_TEXTS.length)];
            LocalDateTime created = postDates[postId].plusMinutes(1 + random.nextInt(60 * 24 * 30));

            rows.add(new Object[]{id, postId, userId, text, created, 0});
        }
        insert("comments",
                "INSERT INTO comments (comment_id, post_id, user_id, content, created_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows);
    }

    private void seedReviews(LocalDateTime[] postDates) {
        List<Object[]> rows = new ArrayList<>();
        int reviewId = 1;
        for (int postId = 1; postId <= POSTS; postId++) {
            Set<Integer> reviewers = new HashSet<>();
            int howMany = random.nextInt(MAX_REVIEWS_PER_POST + 1);
            while (reviewers.size() < howMany) {
                reviewers.add(1 + random.nextInt(USERS));
            }
            for (int userId : reviewers) {
                int rating = 1 + random.nextInt(5);
                LocalDateTime created = postDates[postId].plusHours(1 + random.nextInt(24 * 60));
                rows.add(new Object[]{reviewId++, postId, userId, rating, "Rated " + rating + " out of 5", created});
            }
        }
        insert("reviews",
                "INSERT INTO reviews (review_id, post_id, user_id, rating, review_text, created_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?)",
                rows);
    }

    private void insert(String table, String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += BATCH_SIZE) {
            int to = Math.min(from + BATCH_SIZE, rows.size());
            jdbc.batchUpdate(sql, rows.subList(from, to));
        }
        log.info("  {}: {} rows", table, rows.size());
    }

    private LocalDateTime randomDate() {
        return firstDay.plusMinutes(random.nextInt(60 * 24 * 730));
    }

    private String randomTag() {
        return TAGS[random.nextInt(TAGS.length)];
    }

    private String makeContent(String topic) {
        StringBuilder text = new StringBuilder("In this post we look at " + topic + " and " + randomTag() + ". ");
        for (int i = 0; i < 5; i++) {
            text.append(SENTENCES[random.nextInt(SENTENCES.length)]).append(' ');
        }
        text.append("Related topics: ").append(randomTag()).append(", ").append(randomTag()).append('.');
        return text.toString();
    }
}
