package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.PostRequest;
import com.blogPlatform.III.blog.dto.response.PostResponse;
import com.blogPlatform.III.blog.dto.response.StatsResponse;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.ContentRejectedException;
import com.blogPlatform.III.blog.repository.*;
import com.blogPlatform.III.blog.service.PostService;
import com.blogPlatform.III.blog.service.StatsService;
import com.blogPlatform.III.blog.service.TagService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// not @Transactional: these tests need real commits and rollbacks
@SpringBootTest
@ActiveProfiles("test")
class TransactionTest {

    @Autowired PostService postService;
    @Autowired TagService tagService;
    @Autowired StatsService statsService;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired AuditLogRepository auditLogRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    User author;

    @Autowired CacheManager cacheManager;

    // the cache is shared by all test classes and would keep data of rolled-back tests
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        author = new User();
        author.setUsername("serge");
        author.setEmail("serge@test.com");
        author.setPasswordHash("hash");
        userRepository.save(author);
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM audit_logs");
    }

    @Test
    void createPost_failsHalfway_nothingIsSaved_butAuditStays() {
        PostRequest request = new PostRequest("Half saved?", "Some content", null,
                List.of("brand-new-tag", "x".repeat(60)));

        assertThrows(DataIntegrityViolationException.class, () -> postService.create(author.getId(), request));

        assertEquals(0, postRepository.count());
        assertFalse(tagRepository.existsByName("brand-new-tag"));
        assertEquals(1, auditLogRepository.findByAction("CREATE_POST").size());
    }

    @Test
    void createPost_checkedException_isRolledBack_thanksToRollbackFor() {
        PostRequest request = new PostRequest("Win money", "Visit my casino today", null,
                List.of("money-tag"));

        assertThrows(ContentRejectedException.class, () -> postService.create(author.getId(), request));

        assertEquals(0, postRepository.count());
        assertFalse(tagRepository.existsByName("money-tag"));
        assertEquals(1, auditLogRepository.findByAction("CREATE_POST").size());
    }

    @Test
    void createPost_success_savesPostTagsAndAudit() throws Exception {
        PostResponse created = postService.create(author.getId(), new PostRequest("Good post", "Clean content",
                PostStatus.PUBLISHED, List.of("java", "spring")));

        assertTrue(postRepository.existsById(created.id()));
        assertTrue(tagRepository.existsByName("java"));
        assertTrue(tagRepository.existsByName("spring"));
        assertEquals(0L, created.version());
        assertEquals(1, auditLogRepository.findByAction("CREATE_POST").size());
    }

    @Test
    void findOrCreateTags_withoutTransaction_isRefused() {
        assertThrows(IllegalTransactionStateException.class, () -> tagService.findOrCreate(List.of("lonely")));
        assertFalse(tagRepository.existsByName("lonely"));
    }

    @Test
    void twoConcurrentUpdates_secondOneFails_firstOneIsKept() {
        Long postId = savePost("Original title");

        TransactionTemplate userA = new TransactionTemplate(transactionManager);
        TransactionTemplate userB = new TransactionTemplate(transactionManager);
        userB.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        assertThrows(OptimisticLockingFailureException.class, () ->
                userA.executeWithoutResult(statusA -> {
                    Post copyOfA = postRepository.findById(postId).orElseThrow();

                    userB.executeWithoutResult(statusB -> {
                        Post copyOfB = postRepository.findById(postId).orElseThrow();
                        copyOfB.setTitle("Saved by B");
                    });

                    copyOfA.setTitle("Saved by A");
                }));

        Post result = postRepository.findById(postId).orElseThrow();
        assertEquals("Saved by B", result.getTitle());
        assertEquals(1L, result.getVersion());
    }

    @Test
    void repeatableRead_allCountsComeFromTheSameSnapshot() {
        long[] counts = countTwiceWhileAnotherTransactionInserts(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertEquals(counts[0], counts[1]);
    }

    @Test
    void readCommitted_seesRowsCommittedInTheMiddle() {
        long[] counts = countTwiceWhileAnotherTransactionInserts(TransactionDefinition.ISOLATION_READ_COMMITTED);
        assertEquals(counts[0] + 1, counts[1]);
    }

    @Test
    void stats_numbersFitTogether() {
        savePost("Draft post");
        savePost("Published post", PostStatus.PUBLISHED);

        StatsResponse stats = statsService.getStats();

        assertEquals(1, stats.users());
        assertEquals(2, stats.posts());
        assertEquals(stats.posts(), stats.publishedPosts() + stats.draftPosts());
    }

    private long[] countTwiceWhileAnotherTransactionInserts(int isolationLevel) {
        savePost("Already there");

        TransactionTemplate reader = new TransactionTemplate(transactionManager);
        reader.setIsolationLevel(isolationLevel);
        reader.setReadOnly(true);

        TransactionTemplate writer = new TransactionTemplate(transactionManager);
        writer.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return reader.execute(status -> {
            long before = postRepository.count();
            writer.executeWithoutResult(s -> savePost("Inserted in the middle"));
            long after = postRepository.count();
            return new long[]{before, after};
        });
    }

    private Long savePost(String title) {
        return savePost(title, PostStatus.DRAFT);
    }

    private Long savePost(String title, PostStatus status) {
        Post post = new Post();
        post.setAuthor(userRepository.getReferenceById(author.getId()));
        post.setTitle(title);
        post.setContent("Content of " + title);
        post.setStatus(status);
        return postRepository.save(post).getId();
    }
}
