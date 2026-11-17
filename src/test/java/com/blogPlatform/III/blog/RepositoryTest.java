package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.*;
import com.blogPlatform.III.blog.dto.response.*;
import com.blogPlatform.III.blog.entity.*;
import com.blogPlatform.III.blog.repository.*;
import com.blogPlatform.III.blog.service.PostService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RepositoryTest {

    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired CommentRepository commentRepository;
    @Autowired ReviewRepository reviewRepository;
    @Autowired PostService postService;

    User serge;
    User anna;
    Post springPost;
    Post recordsPost;
    Post cachePost;

    @Autowired CacheManager cacheManager;

    // the cache is shared by all test classes and would keep data of rolled-back tests
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @BeforeEach
    void setUp() {
        serge = saveUser("serge");
        anna = saveUser("anna");
        Tag java = saveTag("java");
        Tag spring = saveTag("spring");

        springPost = savePost(serge, "Learning Spring Data JPA", "Repositories and queries", PostStatus.PUBLISHED, java, spring);
        recordsPost = savePost(serge, "Java records", "Records are short immutable classes", PostStatus.DRAFT, java);
        cachePost = savePost(anna, "Caching tips", "Use Spring Cache with Caffeine", PostStatus.PUBLISHED, spring);

        saveComment(springPost, anna, "Great post");
        saveComment(springPost, serge, "Thanks!");
        saveComment(cachePost, serge, "Useful");

        saveReview(springPost, serge, 5);
        saveReview(springPost, anna, 4);
        saveReview(cachePost, serge, 3);

        postRepository.flush();
    }

    @Test
    void derivedQueries() {
        assertTrue(userRepository.findByUsername("serge").isPresent());
        assertTrue(userRepository.existsByEmail("anna@test.com"));
        assertFalse(userRepository.existsByUsername("nobody"));

        assertEquals(1, postRepository.countByStatus(PostStatus.DRAFT));
        assertTrue(postRepository.existsByAuthorIdAndTitle(serge.getId(), "Java records"));

        assertEquals(2, commentRepository.countByPostId(springPost.getId()));
        assertEquals(2, commentRepository.findByPostId(springPost.getId(), PageRequest.of(0, 10)).getTotalElements());
        assertTrue(reviewRepository.existsByPostIdAndUserId(springPost.getId(), anna.getId()));
        assertTrue(tagRepository.findByName("java").isPresent());
    }

    @Test
    void jpqlQueries() {
        List<PostRatingDto> topRated = postRepository.findTopRated(4.0, PageRequest.of(0, 10)).getContent();
        assertEquals(1, topRated.size());
        assertEquals(springPost.getId(), topRated.get(0).postId());
        assertEquals(4.5, topRated.get(0).averageRating());
        assertEquals(2L, topRated.get(0).reviewCount());

        // page of 1, but the count query sees both posts with an average >= 3 (4.5 and 3.0)
        Page<PostRatingDto> firstOfTwo = postRepository.findTopRated(3.0, PageRequest.of(0, 1));
        assertEquals(1, firstOfTwo.getContent().size());
        assertEquals(2, firstOfTwo.getTotalElements());
        assertEquals(cachePost.getId(), postRepository.findTopRated(3.0, PageRequest.of(1, 1)).getContent().get(0).postId());

        Page<PostSummaryDto> summaries = postRepository.findSummaries(PageRequest.of(0, 10, Sort.by("title")));
        assertEquals(3, summaries.getTotalElements());
        PostSummaryDto first = summaries.getContent().get(0);
        assertEquals("Caching tips", first.title());
        assertEquals("anna", first.author());
        assertEquals(1L, first.commentCount());
        PostSummaryDto springSummary = summaries.getContent().stream()
                .filter(s -> s.postId().equals(springPost.getId()))
                .findFirst().orElseThrow();
        assertEquals(2L, springSummary.commentCount());
    }

    @Test
    void nativeQueries() {
        Page<PopularPostView> popular = postRepository.findPopular(PageRequest.of(0, 10));
        assertEquals(2, popular.getTotalElements());
        assertEquals(springPost.getId(), popular.getContent().get(0).getPostId());
        assertEquals(8.5, popular.getContent().get(0).getScore());
        // LIMIT/OFFSET appended to the native SQL: page 1 of size 1 is the second post
        assertEquals(cachePost.getId(), postRepository.findPopular(PageRequest.of(1, 1)).getContent().get(0).getPostId());

        Page<TagUsageView> usage = tagRepository.findTagUsage(PageRequest.of(0, 10));
        assertEquals(2, usage.getTotalElements());
        assertEquals(2L, usage.getContent().get(0).getPostCount());
    }

    @Test
    void modifyingUpdate() {
        int rows = postRepository.updateStatus(recordsPost.getId(), PostStatus.PUBLISHED, LocalDateTime.now());

        assertEquals(1, rows);
        assertEquals(PostStatus.PUBLISHED, postRepository.findById(recordsPost.getId()).orElseThrow().getStatus());
    }

    @Test
    void specificationFilters() {
        assertEquals(3, search(filter(null, null, null, null)).totalElements());

        assertEquals(2, search(filter(PostStatus.PUBLISHED, null, null, null)).totalElements());

        List<PostResponse> result = search(filter(PostStatus.PUBLISHED, "serge", null, null)).content();
        assertEquals(1, result.size());
        assertEquals("Learning Spring Data JPA", result.get(0).title());

        result = search(filter(PostStatus.DRAFT, null, "JAVA", null)).content();
        assertEquals(1, result.size());
        assertEquals("Java records", result.get(0).title());

        assertEquals(0, search(filter(null, null, null, LocalDate.now().plusDays(1))).totalElements());
    }

    @Test
    void paginationAndSorting() {
        Pageable firstPage = PageRequest.of(0, 2, Sort.by("title"));

        PageResponse<PostResponse> page0 = postService.search(filter(null, null, null, null), firstPage);
        assertEquals(2, page0.content().size());
        assertEquals(3, page0.totalElements());
        assertEquals(2, page0.totalPages());
        assertTrue(page0.first());
        assertFalse(page0.last());
        assertEquals("Caching tips", page0.content().get(0).title());
        assertEquals("Java records", page0.content().get(1).title());

        PageResponse<PostResponse> page1 = postService.search(filter(null, null, null, null), firstPage.next());
        assertEquals(1, page1.content().size());
        assertTrue(page1.last());
        assertEquals("Learning Spring Data JPA", page1.content().get(0).title());

        PageResponse<PostResponse> published = postService.search(
                filter(PostStatus.PUBLISHED, null, null, null), PageRequest.of(0, 1));
        assertEquals(1, published.content().size());
        assertEquals(2, published.totalElements());
    }

    private PageResponse<PostResponse> search(PostFilter filter) {
        return postService.search(filter, PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    private PostFilter filter(PostStatus status, String author, String tag, LocalDate from) {
        return new PostFilter(status, author, tag, null, from, null);
    }

    private User saveUser(String username) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPasswordHash("hash");
        return userRepository.save(user);
    }

    private Tag saveTag(String name) {
        Tag tag = new Tag();
        tag.setName(name);
        return tagRepository.save(tag);
    }

    private Post savePost(User author, String title, String content, PostStatus status, Tag... tags) {
        Post post = new Post();
        post.setAuthor(author);
        post.setTitle(title);
        post.setContent(content);
        post.setStatus(status);
        post.getTags().addAll(List.of(tags));
        return postRepository.save(post);
    }

    private void saveComment(Post post, User user, String content) {
        Comment comment = new Comment();
        comment.setPost(post);
        comment.setUser(user);
        comment.setContent(content);
        commentRepository.save(comment);
    }

    private void saveReview(Post post, User user, int rating) {
        Review review = new Review();
        review.setPost(post);
        review.setUser(user);
        review.setRating(rating);
        reviewRepository.save(review);
    }
}
