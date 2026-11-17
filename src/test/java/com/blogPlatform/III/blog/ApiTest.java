package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.entity.*;
import com.blogPlatform.III.blog.repository.*;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.TestExecutionEvent;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.allOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
// these tests check API behavior (paging, validation, ...) as a logged-in user; who may call what is SecurityConfigTest
// "serge" is created in setUp(), so it is loaded (AppUserDetailsService) just before each test, not before setUp
@WithUserDetails(value = "serge", setupBefore = TestExecutionEvent.TEST_EXECUTION)
class ApiTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired PasswordEncoder passwordEncoder;

    User serge;
    Post post;

    @Autowired CacheManager cacheManager;

    // the cache is shared by all test classes and would keep data of rolled-back tests
    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @BeforeEach
    void setUp() {
        serge = new User();
        serge.setUsername("serge");
        serge.setEmail("serge@test.com");
        serge.setPasswordHash("hash");
        userRepository.save(serge);

        Tag java = new Tag();
        java.setName("java");
        tagRepository.save(java);

        post = new Post();
        post.setAuthor(serge);
        post.setTitle("Hello JPA");
        post.setContent("First post");
        post.setStatus(PostStatus.PUBLISHED);
        post.getTags().add(java);
        postRepository.save(post);
    }

    @Test
    void createUser_returns201_withStandardShape() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "anna", "email": "anna@test.com", "password": "secret123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.message").value("User created successfully"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.data.username").value("anna"))
                .andExpect(jsonPath("$.data.roles[0]").value("READER"))
                .andExpect(jsonPath("$.data.authProvider").value("LOCAL"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andExpect(jsonPath("$.errors").doesNotExist());

        String storedHash = userRepository.findByUsername("anna").orElseThrow().getPasswordHash();
        assertNotEquals("secret123", storedHash);
        assertTrue(passwordEncoder.matches("secret123", storedHash));
    }

    @Test
    void createUser_withBadData_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "", "email": "not-an-email", "password": "123"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.path").value("/api/users"))
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.email").exists())
                .andExpect(jsonPath("$.errors.password").exists())
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void createUser_withTakenUsername_returns409() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "serge", "email": "other@test.com", "password": "secret123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Username 'serge' is already taken"));
    }

    @Test
    void brokenJson_returns400() throws Exception {
        mockMvc.perform(post("/api/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Request body is missing or is not valid JSON"));
    }

    @Test
    void createPost_createsMissingTags() throws Exception {
        mockMvc.perform(post("/api/posts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorId": %d, "title": "Second post", "content": "Text",
                                 "tags": ["Java", "Spring"]}
                                """.formatted(serge.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Post created successfully"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.authorUsername").value("serge"))
                .andExpect(jsonPath("$.data.tags[0]").value("java"))
                .andExpect(jsonPath("$.data.tags[1]").value("spring"));
    }

    @Test
    void getPost_unknownId_returns404() throws Exception {
        mockMvc.perform(get("/api/posts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("Post 999999 not found"))
                .andExpect(jsonPath("$.path").value("/api/posts/999999"));
    }

    @Test
    void filterPosts_withSpecification() throws Exception {
        mockMvc.perform(get("/api/posts")
                        .param("status", "PUBLISHED")
                        .param("author", "serge")
                        .param("tag", "java"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Posts retrieved successfully"))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Hello JPA"));

        mockMvc.perform(get("/api/posts").param("status", "DRAFT"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0))
                .andExpect(jsonPath("$.data.content.length()").value(0));

        mockMvc.perform(get("/api/posts").param("status", "HELLO"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.status").value("invalid value 'HELLO'"));
    }

    @Test
    void posts_defaultPage_isFirst10_newestFirst() throws Exception {
        savePosts(24);

        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(10))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.totalElements").value(25))
                .andExpect(jsonPath("$.data.totalPages").value(3))
                .andExpect(jsonPath("$.data.first").value(true))
                .andExpect(jsonPath("$.data.last").value(false))
                .andExpect(jsonPath("$.data.sort").value("createdAt: DESC"));
    }

    @Test
    void posts_lastPage_hasTheRest() throws Exception {
        savePosts(24);

        mockMvc.perform(get("/api/posts").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(5))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.last").value(true));
    }

    @Test
    void posts_sortByTitle() throws Exception {
        savePosts(5);

        mockMvc.perform(get("/api/posts").param("size", "3").param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("Hello JPA"))
                .andExpect(jsonPath("$.data.content[1].title").value("Post 01"))
                .andExpect(jsonPath("$.data.content[2].title").value("Post 02"))
                .andExpect(jsonPath("$.data.sort").value("title: ASC"));

        mockMvc.perform(get("/api/posts").param("size", "1").param("sort", "title,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].title").value("Post 05"));
    }

    @Test
    void posts_sizeAbove100_returns400() throws Exception {
        mockMvc.perform(get("/api/posts").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").value("page size must be between 1 and 100"));

        mockMvc.perform(get("/api/posts").param("size", "5000"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/posts").param("size", "500").param("sort", "content"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").value(allOf(
                        containsString("page size must be between 1 and 100"),
                        containsString("cannot sort by 'content'"))));

        mockMvc.perform(get("/api/posts").param("size", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    @Test
    void posts_sortByUnknownField_returns400() throws Exception {
        mockMvc.perform(get("/api/posts").param("sort", "content,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Validation failed"))
                .andExpect(jsonPath("$.errors.pageable").value(
                        "cannot sort by 'content'; allowed: id, title, status, createdAt, updatedAt"));

        mockMvc.perform(get("/api/posts").param("sort", "author.passwordHash"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sortField_withTemplateCharacters_isNotInterpreted() throws Exception {
        mockMvc.perform(get("/api/posts").param("sort", "${1+1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").value(
                        "cannot sort by '??1?1?'; allowed: id, title, status, createdAt, updatedAt"));
    }

    @Test
    void search_isOrderedByRelevance_soClientSortIsRefused() throws Exception {
        mockMvc.perform(get("/api/posts/search").param("keyword", "jpa").param("sort", "title"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").value("sorting is not allowed here"));
    }

    @Test
    void feed_cursorPaging_walksThroughAllPosts() throws Exception {
        savePosts(5);

        String firstBody = mockMvc.perform(get("/api/posts/feed").param("size", "4"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(4))
                .andExpect(jsonPath("$.data.hasNext").value(true))
                .andExpect(jsonPath("$.data.nextCursor").exists())
                .andReturn().getResponse().getContentAsString();
        String cursor = JsonPath.read(firstBody, "$.data.nextCursor");

        mockMvc.perform(get("/api/posts/feed").param("size", "4").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.hasNext").value(false))
                .andExpect(jsonPath("$.data.nextCursor").doesNotExist());
    }

    @Test
    void feed_badCursor_returns400() throws Exception {
        mockMvc.perform(get("/api/posts/feed").param("cursor", "not-a-cursor"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors.cursor").value("must be the nextCursor value of the previous response"));

        mockMvc.perform(get("/api/posts/feed").param("cursor", "2025-13-45T10:00,5"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.cursor").value("must be the nextCursor value of the previous response"));
        mockMvc.perform(get("/api/posts/feed").param("cursor", "2025-03-13T09:22,-5"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/posts/feed").param("cursor", ""))
                .andExpect(status().isOk());
    }

    @Test
    void summaries_arePagedAndLight() throws Exception {
        savePosts(3);

        mockMvc.perform(get("/api/posts/summaries").param("size", "2").param("sort", "title,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(4))
                .andExpect(jsonPath("$.data.content[0].title").value("Hello JPA"))
                .andExpect(jsonPath("$.data.content[0].author").value("serge"))
                .andExpect(jsonPath("$.data.content[0].commentCount").value(0))
                .andExpect(jsonPath("$.data.content[0].content").doesNotExist());
    }

    @Test
    void comments_arePaged_oldestFirst() throws Exception {
        for (int i = 1; i <= 3; i++) {
            mockMvc.perform(post("/api/posts/" + post.getId() + "/comments")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"userId\": %d, \"content\": \"Comment %d\"}".formatted(serge.getId(), i)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/posts/" + post.getId() + "/comments").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.content[0].content").value("Comment 1"))
                .andExpect(jsonPath("$.data.sort").value("createdAt: ASC"));

        mockMvc.perform(get("/api/posts/" + post.getId() + "/comments").param("sort", "content"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingRequiredParam_returns400_inSameShape() throws Exception {
        mockMvc.perform(get("/api/posts/search"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.keyword").value("is required"));
    }

    @Test
    void publishPost_usesModifyingQuery() throws Exception {
        post.setStatus(PostStatus.DRAFT);
        postRepository.saveAndFlush(post);

        mockMvc.perform(patch("/api/posts/" + post.getId() + "/publish"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Post published successfully"))
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));
    }

    @Test
    void deletePost_returns200WithMessage_thenGone() throws Exception {
        mockMvc.perform(delete("/api/posts/" + post.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("Post deleted successfully"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(get("/api/posts/" + post.getId()))
                .andExpect(status().isNotFound());
    }

    @Test
    void addComment_thenCount() throws Exception {
        mockMvc.perform(post("/api/posts/" + post.getId() + "/comments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": %d, "content": "Nice!"}
                                """.formatted(serge.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Comment added successfully"))
                .andExpect(jsonPath("$.data.username").value("serge"));

        mockMvc.perform(get("/api/posts/" + post.getId() + "/comments/count"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(1));
    }

    @Test
    void review_onlyOncePerUser_andRatingMax5() throws Exception {
        String url = "/api/posts/" + post.getId() + "/reviews";

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"rating\": 6}".formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").exists());

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"rating\": 5}".formatted(serge.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("Review added successfully"));

        mockMvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"rating\": 4}".formatted(serge.getId())))
                .andExpect(status().isConflict());
    }

    @Test
    void updatePost_withOldVersion_returns409() throws Exception {
        String url = "/api/posts/" + post.getId();

        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Edited by client 1", "content": "New text", "version": 0}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Edited by client 1"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put(url).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Edited by client 2", "content": "Other text", "version": 0}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        "This record was changed by someone else. Load it again and retry your change."));
    }

    @Test
    void updatePost_withoutVersion_returns400() throws Exception {
        mockMvc.perform(put("/api/posts/" + post.getId()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "No version", "content": "Text"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.version").exists());
    }

    @Test
    void createPost_withBannedWord_returns400() throws Exception {
        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorId": %d, "title": "Free money", "content": "Best casino in town"}
                                """.formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Post rejected: it contains the banned word 'casino'"));
    }

    @Test
    void createPost_withTooLongTag_returns400() throws Exception {
        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorId": %d, "title": "Tags", "content": "Text", "tags": ["%s"]}
                                """.formatted(serge.getId(), "x".repeat(60))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['tags[0]']").exists());
    }

    @Test
    void editComment_withOldVersion_returns409() throws Exception {
        String commentsUrl = "/api/posts/" + post.getId() + "/comments";
        String body = mockMvc.perform(post(commentsUrl).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"content\": \"First text\"}".formatted(serge.getId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn().getResponse().getContentAsString();
        String commentId = body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1");

        mockMvc.perform(put(commentsUrl + "/" + commentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Edited\", \"version\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("Edited"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put(commentsUrl + "/" + commentId).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"content\": \"Stale edit\", \"version\": 0}"))
                .andExpect(status().isConflict());
    }

    @Test
    void stats_returnsCounts() throws Exception {
        mockMvc.perform(get("/api/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Stats retrieved successfully"))
                .andExpect(jsonPath("$.data.users").value(1))
                .andExpect(jsonPath("$.data.posts").value(1))
                .andExpect(jsonPath("$.data.publishedPosts").value(1))
                .andExpect(jsonPath("$.data.draftPosts").value(0))
                .andExpect(jsonPath("$.data.tags").value(1));
    }

    @Test
    // the audit row is committed by its own transaction (REQUIRES_NEW). Under MySQL's REPEATABLE READ this test's
    // transaction keeps the snapshot of its first read (loading the logged-in user), so it would never see the row.
    @Transactional(isolation = Isolation.READ_COMMITTED)
    void auditLog_recordsPostActions() throws Exception {
        mockMvc.perform(patch("/api/posts/" + post.getId() + "/publish"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/audit-logs").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].action").value("PUBLISH_POST"))
                .andExpect(jsonPath("$.data.content[0].entityId").value(post.getId()));
    }

    @Test
    void popularPosts_andTagUsage() throws Exception {
        mockMvc.perform(get("/api/posts/popular"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].postId").value(post.getId()))
                .andExpect(jsonPath("$.data.content[0].title").value("Hello JPA"));

        mockMvc.perform(get("/api/tags/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].name").value("java"))
                .andExpect(jsonPath("$.data.content[0].postCount").value(1));
    }

    @Test
    void everyList_isPagedByDefault() throws Exception {
        // url, default size, default order
        String[][] lists = {
                {"/api/users", "20", "username: ASC"},
                {"/api/tags", "20", "name: ASC"},
                {"/api/tags/usage", "20", "UNSORTED"},
                {"/api/posts", "10", "createdAt: DESC"},
                {"/api/posts/summaries", "20", "createdAt: DESC"},
                {"/api/posts/search?keyword=hello", "10", "UNSORTED"},
                {"/api/posts/top-rated", "10", "UNSORTED"},
                {"/api/posts/popular", "10", "UNSORTED"},
                {"/api/posts/" + post.getId() + "/comments", "10", "createdAt: ASC"},
                {"/api/posts/" + post.getId() + "/reviews", "10", "createdAt: DESC"},
                {"/api/audit-logs", "20", "createdAt: DESC"},
        };
        for (String[] list : lists) {
            mockMvc.perform(get(list[0]))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.content").isArray())
                    .andExpect(jsonPath("$.data.page").value(0))
                    .andExpect(jsonPath("$.data.size").value(Integer.parseInt(list[1])))
                    .andExpect(jsonPath("$.data.sort").value(list[2]))
                    .andExpect(jsonPath("$.data.totalElements").isNumber());
        }
    }

    @Test
    void newPagedLists_page_sortWhitelist_andMaxSize() throws Exception {
        User anna = new User();
        anna.setUsername("anna");
        anna.setEmail("anna@test.com");
        anna.setPasswordHash("hash");
        userRepository.save(anna);

        // A → Z: anna, serge. Page 1 of size 1 is serge
        mockMvc.perform(get("/api/users").param("page", "1").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].username").value("serge"))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.last").value(true));

        mockMvc.perform(get("/api/users").param("sort", "email,asc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").exists());

        mockMvc.perform(get("/api/tags").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").exists());

        // rankings have a fixed order
        for (String url : new String[]{"/api/posts/popular", "/api/posts/top-rated", "/api/tags/usage"}) {
            mockMvc.perform(get(url).param("sort", "title,asc"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.pageable").exists());
        }
    }

    @Test
    void cacheStats_countHitsAndMisses() throws Exception {
        String url = "/api/posts/" + post.getId();
        mockMvc.perform(get(url)).andExpect(status().isOk());
        mockMvc.perform(get(url)).andExpect(status().isOk());
        mockMvc.perform(get(url)).andExpect(status().isOk());

        mockMvc.perform(get("/api/cache-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(5))
                .andExpect(jsonPath("$.data[?(@.name == 'posts')].size").value(1))
                .andExpect(jsonPath("$.data[?(@.name == 'posts')].hits").value(2))
                .andExpect(jsonPath("$.data[?(@.name == 'posts')].misses").value(1));
    }

    @Test
    void strictJson_unknownField_returns400() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "anna", "email": "anna@test.com", "password": "secret123", "isAdmin": true}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is not valid"))
                .andExpect(jsonPath("$.errors.isAdmin").value("unknown field"));
    }

    @Test
    void strictJson_textForNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/posts/" + post.getId() + "/comments").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"userId": "%d", "content": "Nice"}
                                """.formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.userId").value("must be a whole number"));
    }

    @Test
    void strictJson_decimalForWholeNumber_returns400() throws Exception {
        mockMvc.perform(post("/api/posts/" + post.getId() + "/reviews").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\": %d, \"rating\": 4.5}".formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rating").value("must be a whole number"));
    }

    @Test
    void strictJson_wrongEnumValue_returns400() throws Exception {
        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"authorId": %d, "title": "Enum test", "content": "Text", "status": "published"}
                                """.formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.status").value("must be one of [DRAFT, PUBLISHED]"));
    }

    @Test
    void strictJson_duplicateField_returns400() throws Exception {
        mockMvc.perform(post("/api/tags").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"one\", \"name\": \"two\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void user_strictRules() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "bad name!", "email": "anna@localhost", "password": "onlyletters"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").value("may contain only letters, digits, '.', '_' and '-'"))
                .andExpect(jsonPath("$.errors.email").value("must be a valid email address"))
                .andExpect(jsonPath("$.errors.password").value("must contain at least one letter and one digit"));
    }

    @Test
    void post_atMost10Tags_andValidTagNames() throws Exception {
        String elevenTags = "\"t1\",\"t2\",\"t3\",\"t4\",\"t5\",\"t6\",\"t7\",\"t8\",\"t9\",\"t10\",\"t11\"";
        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorId\": %d, \"title\": \"Tags\", \"content\": \"Text\", \"tags\": [%s]}"
                                .formatted(serge.getId(), elevenTags)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tags").value("a post can have at most 10 tags"));

        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorId\": %d, \"title\": \"Tags\", \"content\": \"Text\", \"tags\": [\"<script>\"]}"
                                .formatted(serge.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors['tags[0]']").exists());
    }

    @Test
    void pathId_mustBePositive() throws Exception {
        mockMvc.perform(get("/api/posts/0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.id").value("must be greater than 0"));

        mockMvc.perform(get("/api/posts/abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.id").value("invalid value 'abc'"));
    }

    @Test
    void queryParams_areChecked() throws Exception {
        mockMvc.perform(get("/api/posts/search").param("keyword", "ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.keyword").exists());

        mockMvc.perform(get("/api/posts").param("keyword", "ab"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.keyword").exists());

        mockMvc.perform(get("/api/posts/top-rated").param("minRating", "6").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.minRating").exists())
                .andExpect(jsonPath("$.errors.pageable").exists());

        mockMvc.perform(get("/api/posts/popular").param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.pageable").exists());

        mockMvc.perform(get("/api/posts/feed").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.size").exists());

        mockMvc.perform(get("/api/posts").param("author", "x"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.author").exists());
    }

    @Test
    void dateRange_fromAfterTo_returns400() throws Exception {
        mockMvc.perform(get("/api/posts").param("from", "2026-02-01").param("to", "2026-01-01"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.dateRangeValid").value("'from' must be on or before 'to'"));

        mockMvc.perform(get("/api/posts").param("from", "yesterday"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.from").value("invalid value 'yesterday'"));
    }

    private void savePosts(int count) {
        for (int i = 1; i <= count; i++) {
            Post extra = new Post();
            extra.setAuthor(serge);
            extra.setTitle("Post %02d".formatted(i));
            extra.setContent("Extra content " + i);
            extra.setStatus(PostStatus.PUBLISHED);
            postRepository.save(extra);
        }
    }
}
