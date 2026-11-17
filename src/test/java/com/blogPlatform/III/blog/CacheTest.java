package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.*;
import com.blogPlatform.III.blog.dto.response.PopularPostResponse;
import com.blogPlatform.III.blog.dto.response.PostResponse;
import com.blogPlatform.III.blog.entity.*;
import com.blogPlatform.III.blog.exception.ContentRejectedException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.*;
import com.blogPlatform.III.blog.service.*;
import com.github.benmanes.caffeine.cache.Policy;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// not @Transactional: writes are really committed, like in production
@SpringBootTest
@ActiveProfiles("test")
class CacheTest {

    // the defaults of the list endpoints
    static final PageRequest TAGS_A_TO_Z = PageRequest.of(0, 20, Sort.by("name"));
    static final PageRequest FIRST_10 = PageRequest.of(0, 10);

    @Autowired PostService postService;
    @Autowired UserService userService;
    @Autowired TagService tagService;
    @Autowired ReviewService reviewService;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired CacheManager cacheManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    Statistics statistics;
    User author;
    Post post;

    @BeforeEach
    void setUp() {
        cleanUp();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        author = new User();
        author.setUsername("serge");
        author.setEmail("serge@test.com");
        author.setPasswordHash("hash");
        userRepository.save(author);

        Tag java = new Tag();
        java.setName("java");
        tagRepository.save(java);

        post = new Post();
        post.setAuthor(author);
        post.setTitle("Cached post");
        post.setContent("Some content");
        post.setStatus(PostStatus.PUBLISHED);
        post.getTags().add(java);
        postRepository.save(post);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM audit_logs");
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void postById_secondReadComesFromMemory_withoutSqlOrTransaction() {
        statistics.clear();
        PostResponse first = postService.getById(post.getId());
        long sqlForFirstRead = statistics.getPrepareStatementCount();
        assertEquals(1, statistics.getTransactionCount());

        statistics.clear();
        PostResponse second = postService.getById(post.getId());

        assertTrue(sqlForFirstRead > 0);
        assertEquals(0, statistics.getPrepareStatementCount());
        assertEquals(0, statistics.getTransactionCount());
        assertEquals(first, second);
    }

    @Test
    void userById_andTagList_areCached() {
        userService.getById(author.getId());
        tagService.getAll(TAGS_A_TO_Z);

        statistics.clear();
        userService.getById(author.getId());
        tagService.getAll(TAGS_A_TO_Z);

        assertEquals(0, statistics.getPrepareStatementCount());

        // another page is another cache entry
        tagService.getAll(PageRequest.of(1, 20, Sort.by("name")));
        assertTrue(statistics.getPrepareStatementCount() > 0);
    }

    @Test
    void updatePost_putsTheNewVersionIntoTheCache() throws Exception {
        postService.getById(post.getId());

        postService.update(post.getId(), new PostUpdateRequest("New title", "New content", null, null, 0L));

        statistics.clear();
        PostResponse cached = postService.getById(post.getId());
        assertEquals("New title", cached.title());
        assertEquals(1L, cached.version());
        assertEquals(0, statistics.getPrepareStatementCount());
    }

    @Test
    void failedUpdate_leavesCacheAndDatabaseUnchanged() {
        postService.getById(post.getId());

        assertThrows(ContentRejectedException.class, () -> postService.update(post.getId(),
                new PostUpdateRequest("Spam title", "Visit my casino", null, null, 0L)));

        assertEquals("Cached post", postService.getById(post.getId()).title());
        assertEquals("Cached post", postRepository.findById(post.getId()).orElseThrow().getTitle());
    }

    @Test
    void deletePost_removesItFromTheCache() {
        postService.getById(post.getId());

        postService.delete(post.getId());

        assertThrows(ResourceNotFoundException.class, () -> postService.getById(post.getId()));
    }

    @Test
    void newReview_evictsPopularAndTopRated() {
        List<PopularPostResponse> before = postService.getPopular(FIRST_10).content();
        assertEquals(0L, before.get(0).reviewCount());
        assertTrue(postService.getTopRated(4.0, FIRST_10).content().isEmpty());

        reviewService.add(post.getId(), author.getId(), new ReviewRequest(5, "Great"));

        PopularPostResponse after = postService.getPopular(FIRST_10).content().get(0);
        assertEquals(1L, after.reviewCount());
        assertEquals(5.0, after.averageRating());
        assertEquals(1, postService.getTopRated(4.0, FIRST_10).totalElements());
    }

    @Test
    void newTag_evictsTheTagList_alsoWhenAPostCreatesIt() throws Exception {
        assertEquals(1, tagService.getAll(TAGS_A_TO_Z).totalElements());

        tagService.create(new TagRequest("spring"));
        assertEquals(2, tagService.getAll(TAGS_A_TO_Z).totalElements());

        postService.create(author.getId(), new PostRequest("Tagged post", "Text", null, List.of("docker")));
        assertEquals(List.of("docker", "java", "spring"),
                tagService.getAll(TAGS_A_TO_Z).content().stream().map(tag -> tag.name()).toList());
    }

    @Test
    void renamedAuthor_isShownInCachedPosts() {
        assertEquals("serge", postService.getById(post.getId()).authorUsername());

        userService.update(author.getId(), new UserRequest("serge_new", "serge@test.com", "secret123"));

        assertEquals("serge_new", userService.getById(author.getId()).username());
        assertEquals("serge_new", postService.getById(post.getId()).authorUsername());
    }

    @Test
    void everyCacheHasItsOwnSizeAndTtl() {
        assertCacheConfig(CacheConfig.POSTS, 10_000, Duration.ofMinutes(10));
        assertCacheConfig(CacheConfig.USERS, 5_000, Duration.ofMinutes(30));
        assertCacheConfig(CacheConfig.TAGS, 100, Duration.ofHours(1));
        assertCacheConfig(CacheConfig.POPULAR_POSTS, 20, Duration.ofSeconds(60));
        assertCacheConfig(CacheConfig.TOP_RATED_POSTS, 50, Duration.ofSeconds(60));
    }

    private void assertCacheConfig(String name, long maxEntries, Duration ttl) {
        Policy<Object, Object> policy = ((CaffeineCache) cacheManager.getCache(name)).getNativeCache().policy();
        assertEquals(maxEntries, policy.eviction().orElseThrow().getMaximum(), name + " max size");
        assertEquals(ttl, policy.expireAfterWrite().orElseThrow().getExpiresAfter(), name + " TTL");
        assertTrue(policy.isRecordingStats(), name + " records stats");
    }
}
