package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.PostFilter;
import com.blogPlatform.III.blog.dto.response.CursorPageResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.PostResponse;
import com.blogPlatform.III.blog.entity.*;
import com.blogPlatform.III.blog.repository.*;
import com.blogPlatform.III.blog.service.CommentService;
import com.blogPlatform.III.blog.service.PostService;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

// not @Transactional: InnoDB updates the FULLTEXT index only at commit
@SpringBootTest
@ActiveProfiles("test")
class QueryOptimizationTest {

    @Autowired PostService postService;
    @Autowired CommentService commentService;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired JdbcTemplate jdbc;

    Statistics statistics;
    List<User> authors = new ArrayList<>();
    List<Tag> tags = new ArrayList<>();
    Post dockerBasics;

    @Autowired CacheManager cacheManager;

    // the cache is shared by all test classes and would keep data of rolled-back tests
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @BeforeEach
    void setUp() {
        cleanDatabase();
        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();

        for (String name : List.of("anna", "ben", "carla")) {
            authors.add(saveUser(name));
        }
        for (String name : List.of("java", "spring", "docker", "mysql")) {
            Tag tag = new Tag();
            tag.setName(name);
            tags.add(tagRepository.save(tag));
        }

        for (int i = 1; i <= 24; i++) {
            String content = (i % 4 == 0)
                    ? "Notes number " + i + ". Today we try docker for local testing."
                    : "Notes number " + i + ". Today we write some java code.";
            PostStatus status = (i % 2 == 0) ? PostStatus.PUBLISHED : PostStatus.DRAFT;
            savePost(authors.get(i % 3), "Daily notes " + i, content, status, tags.get(i % 4), tags.get((i + 1) % 4));
        }
        dockerBasics = savePost(authors.get(0), "Docker basics", "Docker images, docker volumes and docker compose.",
                PostStatus.PUBLISHED, tags.get(2));
    }

    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM tags");
    }

    @Test
    void fullTextSearch_findsTheWord_bestMatchFirst() {
        PageResponse<PostResponse> result = postService.searchByKeyword("DOCKER", PageRequest.of(0, 10));

        assertEquals(7, result.totalElements());
        assertEquals("Docker basics", result.content().get(0).title());
    }

    @Test
    void fullTextSearch_matchesWholeWordsOnly() {
        assertEquals(0, postService.searchByKeyword("dock", PageRequest.of(0, 10)).totalElements());
    }

    @Test
    void keywordFilter_usesFullText_andCombinesWithOtherFilters() {
        PostFilter publishedDocker = new PostFilter(PostStatus.PUBLISHED, null, null, "docker", null, null);
        PageResponse<PostResponse> result = postService.search(publishedDocker, firstPage());

        assertEquals(7, result.totalElements());
        result.content().forEach(post -> assertEquals(PostStatus.PUBLISHED, post.status()));
    }

    @Test
    void postList_needsOnly4SqlStatements_noNPlusOne() {
        statistics.clear();

        PageResponse<PostResponse> page = postService.search(noFilter(), firstPage());

        assertEquals(20, page.content().size());
        page.content().forEach(post -> {
            assertNotNull(post.authorUsername());
            assertFalse(post.tags().isEmpty());
        });
        assertTrue(statistics.getPrepareStatementCount() <= 4,
                "expected at most 4 SQL statements, was " + statistics.getPrepareStatementCount());
    }

    @Test
    void commentList_needsOnly3SqlStatements_noNPlusOne() {
        for (int i = 0; i < 15; i++) {
            Comment comment = new Comment();
            comment.setPost(dockerBasics);
            comment.setUser(authors.get(i % 3));
            comment.setContent("Comment " + i);
            commentRepository.save(comment);
        }
        statistics.clear();

        var page = commentService.getByPost(dockerBasics.getId(), PageRequest.of(0, 10, Sort.by("createdAt")));

        assertEquals(10, page.content().size());
        assertTrue(statistics.getPrepareStatementCount() <= 3,
                "expected at most 3 SQL statements, was " + statistics.getPrepareStatementCount());
    }

    @Test
    void keysetFeed_returnsEveryPostOnce_inTheSameOrderAsOffsetPaging() {
        List<Long> fromFeed = new ArrayList<>();
        String cursor = null;
        do {
            CursorPageResponse<PostResponse> window = postService.feed(cursor, 7);
            window.content().forEach(post -> fromFeed.add(post.id()));
            cursor = window.nextCursor();
        } while (cursor != null);

        List<Long> expected = postRepository.findAll(PostService.FEED_SORT).stream().map(Post::getId).toList();

        assertEquals(25, fromFeed.size());
        assertEquals(25, new HashSet<>(fromFeed).size());
        assertEquals(expected, fromFeed);
    }

    private PageRequest firstPage() {
        return PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "createdAt"));
    }

    private PostFilter noFilter() {
        return new PostFilter(null, null, null, null, null, null);
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPasswordHash("hash");
        return userRepository.save(user);
    }

    private Post savePost(User author, String title, String content, PostStatus status, Tag... postTags) {
        Post post = new Post();
        post.setAuthor(author);
        post.setTitle(title);
        post.setContent(content);
        post.setStatus(status);
        post.getTags().addAll(List.of(postTags));
        return postRepository.save(post);
    }
}
