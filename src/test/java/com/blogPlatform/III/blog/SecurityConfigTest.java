package com.blogPlatform.III.blog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityConfigTest {

    @Autowired MockMvc mockMvc;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void readingTheBlog_needsNoLogin() throws Exception {
        mockMvc.perform(get("/api/posts")).andExpect(status().isOk());
        mockMvc.perform(get("/api/posts/feed")).andExpect(status().isOk());
        mockMvc.perform(get("/api/tags")).andExpect(status().isOk());
        // 404 (not 401): the request got past security and the controller looked for the post
        mockMvc.perform(get("/api/posts/999999/comments")).andExpect(status().isNotFound());
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
    }

    @Test
    void signUp_needsNoLogin() throws Exception {
        mockMvc.perform(post("/api/users").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "anna", "email": "anna@test.com", "password": "secret123"}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    void writing_withoutLogin_is401_inTheApiShape() throws Exception {
        mockMvc.perform(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"authorId\": 1, \"title\": \"Hello\", \"content\": \"Text\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.message").value("Authentication required: please log in"))
                .andExpect(jsonPath("$.path").value("/api/posts"))
                .andExpect(jsonPath("$.timestamp").exists())
                // HTTP Basic is off: no "WWW-Authenticate: Basic" header, so no browser password pop-up
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void protectedReads_withoutLogin_are401() throws Exception {
        for (String url : new String[]{"/api/users", "/api/stats", "/api/audit-logs", "/api/cache-stats"}) {
            mockMvc.perform(get(url)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void anyLoggedInUser_passesTheLoginRule() throws Exception {
        mockMvc.perform(get("/api/stats").with(user("reader").roles("READER")))
                .andExpect(status().isOk());
    }

    @Test
    void adminArea_needsTheAdminRole() throws Exception {
        String url = "/api/admin/anything";

        mockMvc.perform(get(url)).andExpect(status().isUnauthorized());

        mockMvc.perform(get(url).with(user("author").roles("AUTHOR")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("You do not have permission to do this"));

        // 404 (not 403): an admin gets past security; there is simply no endpoint there yet
        mockMvc.perform(get(url).with(user("admin").roles("ADMIN")))
                .andExpect(status().isNotFound());
    }

    @Test
    void stateless_noSessionIsCreated() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/stats").with(user("reader").roles("READER")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Set-Cookie"))
                .andReturn();

        assertNull(result.getRequest().getSession(false));
    }
}
