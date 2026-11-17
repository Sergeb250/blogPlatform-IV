package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.CommentRequest;
import com.blogPlatform.III.blog.dto.request.ReviewRequest;
import com.blogPlatform.III.blog.entity.*;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.repository.RoleRepository;
import com.blogPlatform.III.blog.repository.TagRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.JwtService;
import com.blogPlatform.III.blog.service.CommentService;
import com.blogPlatform.III.blog.service.ReviewService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Who may call what (Phase 4). Every request carries a real signed JWT, so the roles come from the token
// exactly like in production.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RbacTest {

    private static final String DENIED = "You do not have permission to do this";
    private static final String UPDATE_POST = """
            {"title": "Changed title", "content": "Changed text", "version": 0}
            """;

    @Autowired MockMvc mockMvc;
    @Autowired JwtService jwtService;
    @Autowired UserRepository userRepository;
    @Autowired RoleRepository roleRepository;
    @Autowired PostRepository postRepository;
    @Autowired TagRepository tagRepository;
    @Autowired CommentService commentService;
    @Autowired ReviewService reviewService;
    @Autowired CacheManager cacheManager;

    User reader;
    User author;
    User otherAuthor;
    User admin;
    Post othersPost;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        reader = user("reader", RoleName.READER);
        author = user("author", RoleName.AUTHOR);
        otherAuthor = user("other", RoleName.AUTHOR);
        admin = user("admin", RoleName.ADMIN);
        othersPost = savePost(otherAuthor, "Someone else's post");
    }

    // ---------- posts ----------

    @Test
    void reader_cannotCreateAPost() throws Exception {
        as(reader, post("/api/posts"), """
                {"title": "My first post", "content": "Text"}
                """)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value(DENIED))
                .andExpect(jsonPath("$.path").value("/api/posts"));
    }

    @Test
    void author_createsAPost_andIsTheAuthorFromTheToken() throws Exception {
        as(author, post("/api/posts"), """
                {"title": "My first post", "content": "Text"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.authorId").value(author.getId()))
                .andExpect(jsonPath("$.data.authorUsername").value("author"));
    }

    @Test
    void authorIdInTheBody_isRefused() throws Exception {
        as(author, post("/api/posts"), """
                {"authorId": %d, "title": "Posting as someone else", "content": "Text"}
                """.formatted(otherAuthor.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.authorId").value("unknown field"));
    }

    @Test
    void author_cannotChangeSomeoneElsesPost() throws Exception {
        String url = "/api/posts/" + othersPost.getId();

        as(author, put(url), UPDATE_POST).andExpect(status().isForbidden()).andExpect(jsonPath("$.message").value(DENIED));
        as(author, patch(url + "/publish")).andExpect(status().isForbidden());
        as(author, delete(url)).andExpect(status().isForbidden());

        assertEquals("Someone else's post", postRepository.findById(othersPost.getId()).orElseThrow().getTitle());
    }

    @Test
    void author_changesTheirOwnPost() throws Exception {
        Post own = savePost(author, "My own post");

        as(author, put("/api/posts/" + own.getId()), UPDATE_POST)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("Changed title"));
        as(author, delete("/api/posts/" + own.getId())).andExpect(status().isOk());
    }

    @Test
    void admin_mayChangeAnyPost() throws Exception {
        String url = "/api/posts/" + othersPost.getId();

        as(admin, put(url), UPDATE_POST).andExpect(status().isOk());
        as(admin, delete(url)).andExpect(status().isOk());
    }

    @Test
    void ownPost_stillNeedsTheAuthorRole() throws Exception {
        // a former author (now only a reader) can no longer change the posts they wrote
        Post written = savePost(reader, "Written while I was an author");

        as(reader, put("/api/posts/" + written.getId()), UPDATE_POST).andExpect(status().isForbidden());
    }

    @Test
    void missingPost_is404_not403() throws Exception {
        as(author, put("/api/posts/999999"), UPDATE_POST)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Post 999999 not found"));
    }

    // ---------- comments and reviews ----------

    @Test
    void comments_onlyTheirWriterOrAnAdminMayChangeThem() throws Exception {
        Long commentId = commentService.add(othersPost.getId(), reader.getId(), new CommentRequest("First!")).id();
        String url = "/api/posts/" + othersPost.getId() + "/comments/" + commentId;
        String edit = """
                {"content": "Edited", "version": 0}
                """;

        // the post's author does not own the comments under it
        as(otherAuthor, put(url), edit).andExpect(status().isForbidden());
        as(otherAuthor, delete(url)).andExpect(status().isForbidden());

        as(reader, put(url), edit).andExpect(status().isOk()).andExpect(jsonPath("$.data.content").value("Edited"));
        as(admin, delete(url)).andExpect(status().isOk());
    }

    @Test
    void anyLoggedInUser_comments_andReviews_asThemself() throws Exception {
        String postUrl = "/api/posts/" + othersPost.getId();

        as(reader, post(postUrl + "/comments"), """
                {"content": "Nice post"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("reader"));
        as(reader, post(postUrl + "/reviews"), """
                {"rating": 5}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value("reader"));
    }

    @Test
    void reviews_onlyTheirWriterOrAnAdminMayDeleteThem() throws Exception {
        Long reviewId = reviewService.add(othersPost.getId(), reader.getId(), new ReviewRequest(4, null)).id();
        String url = "/api/posts/" + othersPost.getId() + "/reviews/" + reviewId;

        as(author, delete(url)).andExpect(status().isForbidden());
        as(reader, delete(url)).andExpect(status().isOk());
    }

    // ---------- tags ----------

    @Test
    void tags_authorsCreate_onlyAdminsDelete() throws Exception {
        as(reader, post("/api/tags"), "{\"name\": \"rbac\"}").andExpect(status().isForbidden());
        String body = as(author, post("/api/tags"), "{\"name\": \"rbac\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String url = "/api/tags/" + body.replaceAll(".*\"data\":\\{\"id\":(\\d+).*", "$1");

        as(author, delete(url)).andExpect(status().isForbidden());
        as(admin, delete(url)).andExpect(status().isOk());
    }

    // ---------- users and roles ----------

    @Test
    void users_seeAndChangeOnlyTheirOwnAccount() throws Exception {
        as(reader, get("/api/users")).andExpect(status().isForbidden());
        as(admin, get("/api/users")).andExpect(status().isOk());

        as(reader, get("/api/users/" + reader.getId())).andExpect(status().isOk());
        as(reader, get("/api/users/by-username/reader")).andExpect(status().isOk());
        as(reader, get("/api/users/" + author.getId())).andExpect(status().isForbidden());
        as(reader, get("/api/users/by-username/author")).andExpect(status().isForbidden());
        as(admin, get("/api/users/" + author.getId())).andExpect(status().isOk());

        String update = """
                {"username": "renamed", "email": "renamed@test.com", "password": "secret123"}
                """;
        as(reader, put("/api/users/" + author.getId()), update).andExpect(status().isForbidden());
        as(reader, delete("/api/users/" + author.getId())).andExpect(status().isForbidden());
        as(reader, put("/api/users/" + reader.getId()), update).andExpect(status().isOk());
    }

    @Test
    void onlyAnAdmin_changesRoles_andTheUserGetsThemWithTheNextToken() throws Exception {
        String url = "/api/users/" + reader.getId() + "/roles";
        String oldToken = bearer(reader);

        as(reader, put(url), "{\"roles\": [\"ADMIN\"]}").andExpect(status().isForbidden());
        as(author, put(url), "{\"roles\": [\"AUTHOR\"]}").andExpect(status().isForbidden());

        as(admin, put(url), "{\"roles\": [\"AUTHOR\", \"READER\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Roles updated successfully"))
                .andExpect(jsonPath("$.data.roles.length()").value(2));

        String newPost = """
                {"title": "Now I am an author", "content": "Text"}
                """;
        // the old token was signed with READER only
        mockMvc.perform(post("/api/posts").header(HttpHeaders.AUTHORIZATION, oldToken)
                        .contentType(MediaType.APPLICATION_JSON).content(newPost))
                .andExpect(status().isForbidden());
        as(userRepository.findById(reader.getId()).orElseThrow(), post("/api/posts"), newPost)
                .andExpect(status().isCreated());
    }

    @Test
    void roleChange_rules() throws Exception {
        as(admin, put("/api/users/" + reader.getId() + "/roles"), "{\"roles\": []}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.roles").value("a user needs at least one role"));
        as(admin, put("/api/users/" + reader.getId() + "/roles"), "{\"roles\": [\"OWNER\"]}")
                .andExpect(status().isBadRequest());
        as(admin, put("/api/users/" + admin.getId() + "/roles"), "{\"roles\": [\"READER\"]}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("You cannot remove the ADMIN role from your own account"));
    }

    @Test
    void adminOnlyReads() throws Exception {
        for (String url : new String[]{"/api/audit-logs", "/api/cache-stats"}) {
            as(author, get(url)).andExpect(status().isForbidden());
            as(admin, get(url)).andExpect(status().isOk());
        }
        // plain counts: any logged-in user
        as(reader, get("/api/stats")).andExpect(status().isOk());
    }

    // ---------- helpers ----------

    private ResultActions as(User user, MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request.header(HttpHeaders.AUTHORIZATION, bearer(user)));
    }

    private ResultActions as(User user, MockHttpServletRequestBuilder request, String json) throws Exception {
        return as(user, request.contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private String bearer(User user) {
        return "Bearer " + jwtService.issue(AuthUser.from(user)).value();
    }

    private User user(String username, RoleName role) {
        User user = new User();
        user.setUsername(username);
        user.setEmail(username + "@test.com");
        user.setPasswordHash("hash");
        user.getRoles().addAll(roleRepository.findByNameIn(List.of(role)));
        return userRepository.save(user);
    }

    private Post savePost(User author, String title) {
        Post post = new Post();
        post.setAuthor(author);
        post.setTitle(title);
        post.setContent("Text");
        post.setStatus(PostStatus.PUBLISHED);
        return postRepository.save(post);
    }
}
