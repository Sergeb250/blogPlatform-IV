package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.*;
import com.blogPlatform.III.blog.dto.response.*;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.exception.ContentRejectedException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.service.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

// not @Transactional: real commits, then plain SQL shows what is really in the tables
@SpringBootTest
@ActiveProfiles("test")
class SoftDeleteTest {

    static final PostFilter NO_FILTER = new PostFilter(null, null, null, null, null, null);
    static final PageRequest FIRST_PAGE = PageRequest.of(0, 10);
    static final PageRequest TAGS_A_TO_Z = PageRequest.of(0, 20, Sort.by("name"));

    @Autowired UserService userService;
    @Autowired PostService postService;
    @Autowired CommentService commentService;
    @Autowired ReviewService reviewService;
    @Autowired TagService tagService;
    @Autowired StatsService statsService;
    @Autowired PostRepository postRepository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CacheManager cacheManager;

    UserResponse author;
    UserResponse reader;
    PostResponse post;

    @BeforeEach
    void setUp() throws ContentRejectedException {
        cleanDatabase();
        author = userService.create(new UserRequest("author", "author@test.com", "secret123", RoleName.AUTHOR));
        reader = userService.create(new UserRequest("reader", "reader@test.com", "secret123", null));
        post = postService.create(new PostRequest(author.id(), "Soft delete explained",
                "Deleted rows stay in the zebra table", PostStatus.PUBLISHED, List.of("java", "spring")));
    }

    // a real DELETE (not the API): ON DELETE CASCADE removes posts, comments, reviews and links
    @AfterEach
    void cleanDatabase() {
        jdbc.update("DELETE FROM users");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM audit_logs");
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    // plain SQL sees every row; it fails if the row is really gone
    private boolean isSoftDeleted(String table, String idColumn, Long id) {
        return jdbc.queryForObject("SELECT deleted_at IS NOT NULL FROM " + table + " WHERE " + idColumn + " = ?",
                Boolean.class, id);
    }

    @Test
    void deletePost_keepsTheRows_butEveryReadSkipsThem() {
        CommentResponse comment = commentService.add(post.id(), new CommentRequest(reader.id(), "Nice"));
        ReviewResponse review = reviewService.add(post.id(), new ReviewRequest(reader.id(), 5, null));
        assertEquals(1, postService.searchByKeyword("zebra", FIRST_PAGE).totalElements());

        postService.delete(post.id());

        // still in the tables, with their data, only marked
        assertTrue(isSoftDeleted("posts", "post_id", post.id()));
        assertTrue(isSoftDeleted("comments", "comment_id", comment.id()));
        assertTrue(isSoftDeleted("reviews", "review_id", review.id()));
        assertEquals("Soft delete explained",
                jdbc.queryForObject("SELECT title FROM posts WHERE post_id = ?", String.class, post.id()));

        // findById, derived queries, Specifications, JPQL: Hibernate adds "deleted_at IS NULL"
        assertThrows(ResourceNotFoundException.class, () -> postService.getById(post.id()));
        assertThrows(ResourceNotFoundException.class, () -> commentService.countByPost(post.id()));
        assertEquals(0, postService.search(NO_FILTER, FIRST_PAGE).totalElements());
        assertEquals(0, postService.getSummaries(FIRST_PAGE).totalElements());
        assertTrue(postService.feed(null, 10).content().isEmpty());
        assertTrue(postService.getTopRated(1.0, FIRST_PAGE).content().isEmpty());
        // native SQL: filtered by hand in the repository
        assertEquals(0, postService.searchByKeyword("zebra", FIRST_PAGE).totalElements());
        assertEquals(0, postService.getPopular(FIRST_PAGE).totalElements());
        assertTrue(tagService.getUsage(FIRST_PAGE).content().stream().allMatch(tag -> tag.getPostCount() == 0));

        StatsResponse stats = statsService.getStats();
        assertEquals(0, stats.posts());
        assertEquals(0, stats.comments());
        assertEquals(0, stats.reviews());
        assertEquals(2, stats.users());
    }

    @Test
    void deleteWithAnOldVersion_isRefused_andThePostStaysLive() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(OptimisticLockingFailureException.class, () -> tx.executeWithoutResult(status -> {
            Post loaded = postRepository.findById(post.id()).orElseThrow();
            // someone else edits the post after we read it
            jdbc.update("UPDATE posts SET version = version + 1 WHERE post_id = ?", post.id());

            postRepository.delete(loaded);
            postRepository.flush();   // UPDATE ... WHERE version = <old> -> 0 rows -> verify = RowCount fails
        }));

        assertFalse(isSoftDeleted("posts", "post_id", post.id()));
    }

    @Test
    void deletePostTwice_secondTimeIsNotFound() {
        postService.delete(post.id());

        assertThrows(ResourceNotFoundException.class, () -> postService.delete(post.id()));
    }

    @Test
    void deleteUser_hidesTheAccount_butKeepsTheirPostsCommentsAndReviews() throws Exception {
        PostResponse readersPost = postService.create(new PostRequest(reader.id(), "Reader's post", "Hello",
                PostStatus.PUBLISHED, null));
        CommentResponse onAuthorsPost = commentService.add(post.id(), new CommentRequest(reader.id(), "on author's post"));
        ReviewResponse reviewOfAuthorsPost = reviewService.add(post.id(), new ReviewRequest(reader.id(), 4, null));
        CommentResponse byAuthor = commentService.add(readersPost.id(), new CommentRequest(author.id(), "by the author"));
        ReviewResponse reviewByAuthor = reviewService.add(readersPost.id(), new ReviewRequest(author.id(), 5, null));
        CommentResponse readersOwn = commentService.add(readersPost.id(), new CommentRequest(reader.id(), "reader's own"));

        userService.delete(author.id());

        assertTrue(isSoftDeleted("users", "user_id", author.id()));
        assertFalse(isSoftDeleted("posts", "post_id", post.id()));
        assertFalse(isSoftDeleted("comments", "comment_id", onAuthorsPost.id()));
        assertFalse(isSoftDeleted("reviews", "review_id", reviewOfAuthorsPost.id()));
        assertFalse(isSoftDeleted("comments", "comment_id", byAuthor.id()));
        assertFalse(isSoftDeleted("reviews", "review_id", reviewByAuthor.id()));
        assertFalse(isSoftDeleted("comments", "comment_id", readersOwn.id()));

        PostResponse keptPost = postService.getById(post.id());
        assertEquals("author", keptPost.authorUsername());
        assertEquals(author.id(), keptPost.authorId());
        assertEquals(List.of("on author's post"), commentService.getByPost(post.id(), FIRST_PAGE).content()
                .stream().map(CommentResponse::content).toList());
        assertEquals(Set.of("by the author", "reader's own"), new HashSet<>(commentService
                .getByPost(readersPost.id(), FIRST_PAGE).content().stream().map(CommentResponse::content).toList()));
        assertEquals(1, reviewService.getByPost(post.id(), FIRST_PAGE).totalElements());
        assertEquals(1, reviewService.getByPost(readersPost.id(), FIRST_PAGE).totalElements());

        assertThrows(ResourceNotFoundException.class, () -> userService.getById(author.id()));
        assertThrows(ResourceNotFoundException.class, () -> postService.create(
                new PostRequest(author.id(), "After delete", "Nope", PostStatus.PUBLISHED, null)));
        StatsResponse stats = statsService.getStats();
        assertEquals(1, stats.users());
        assertEquals(2, stats.posts());
        assertEquals(3, stats.comments());
        assertEquals(2, stats.reviews());
    }

    @Test
    void deletedUser_usernameAndEmailCanBeUsedAgain() {
        userService.delete(reader.id());

        UserResponse newReader = userService.create(new UserRequest("reader", "reader@test.com", "secret123", null));

        assertNotEquals(reader.id(), newReader.id());
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE username = 'reader'", Integer.class));
    }

    @Test
    void twoLiveUsersWithTheSameUsername_areStillBlockedByTheDatabase() {
        // straight SQL, past the service's own check: the unique key (username, not_deleted) still works
        assertThrows(DuplicateKeyException.class, () -> jdbc.update("""
                INSERT INTO users (username, email, password_hash, created_at)
                VALUES ('reader', 'other@test.com', 'hash', NOW())
                """));
    }

    @Test
    void deletedReview_theSameUserCanReviewAgain_andItNoLongerCounts() {
        ReviewResponse first = reviewService.add(post.id(), new ReviewRequest(reader.id(), 1, "meh"));
        reviewService.delete(post.id(), first.id());

        ReviewResponse second = reviewService.add(post.id(), new ReviewRequest(reader.id(), 5, "changed my mind"));

        assertTrue(isSoftDeleted("reviews", "review_id", first.id()));
        assertEquals(List.of(second.id()), reviewService.getByPost(post.id(), FIRST_PAGE).content().stream()
                .map(ReviewResponse::id).toList());
        assertEquals(5.0, statsService.getStats().averageRating());
        assertEquals(5.0, postService.getTopRated(1.0, FIRST_PAGE).content().get(0).averageRating());
        assertEquals(1L, postService.getPopular(FIRST_PAGE).content().get(0).reviewCount());
    }

    @Test
    void deletedPost_itsTitleCanBeUsedAgain() throws ContentRejectedException {
        postService.delete(post.id());

        PostResponse again = postService.create(new PostRequest(author.id(), "Soft delete explained", "Second try",
                null, null));

        assertNotEquals(post.id(), again.id());
    }

    @Test
    void deletedTag_disappearsFromPosts_andItsNameCanBeUsedAgain() {
        Long javaId = tagService.getAll(TAGS_A_TO_Z).content().stream()
                .filter(tag -> tag.name().equals("java")).findFirst().orElseThrow().id();

        tagService.delete(javaId);

        assertTrue(isSoftDeleted("tags", "tag_id", javaId));
        // the link row stays (a restore would bring it back), but nothing reads it any more
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM post_tags WHERE tag_id = ?", Integer.class, javaId));
        assertEquals(List.of("spring"), postService.getById(post.id()).tags());
        assertEquals(List.of("spring"), tagService.getAll(TAGS_A_TO_Z).content().stream().map(TagResponse::name).toList());
        assertEquals(0, postService.search(new PostFilter(null, null, "java", null, null, null), FIRST_PAGE).totalElements());
        assertTrue(tagService.getUsage(FIRST_PAGE).content().stream().noneMatch(tag -> tag.getName().equals("java")));

        TagResponse newJava = tagService.create(new TagRequest("java"));

        assertNotEquals(javaId, newJava.id());
        assertEquals(List.of("spring"), postService.getById(post.id()).tags());
    }

    @Test
    void deleteComment_keepsTheRow_andTheCountDrops() {
        CommentResponse comment = commentService.add(post.id(), new CommentRequest(reader.id(), "typo"));

        commentService.delete(post.id(), comment.id());

        assertTrue(isSoftDeleted("comments", "comment_id", comment.id()));
        assertEquals(0, commentService.countByPost(post.id()));
        assertEquals(0, commentService.getByPost(post.id(), FIRST_PAGE).totalElements());
        assertThrows(ResourceNotFoundException.class, () -> commentService.delete(post.id(), comment.id()));
    }
}
