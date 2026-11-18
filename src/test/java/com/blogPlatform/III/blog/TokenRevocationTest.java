package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.service.UserService;
import com.jayway.jsonpath.JsonPath;
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
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class TokenRevocationTest {

    static final String REVOKED = "Token has been revoked, please log in again";

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired CacheManager cacheManager;

    Long annaId;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        annaId = userService.register(new UserRequest("anna", "anna@test.com", "secret123")).id();
    }

    @Test
    void afterLogout_theSameTokenIsRefused() throws Exception {
        String token = login("anna", "secret123");
        me(token).andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"));

        // the token is still signed and not expired, but it is on the blacklist now
        me(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(REVOKED));
    }

    @Test
    void logout_onOneDevice_keepsTheOtherLoggedIn() throws Exception {
        String laptop = login("anna", "secret123");
        String phone = login("anna", "secret123");

        mockMvc.perform(post("/api/auth/logout").header(HttpHeaders.AUTHORIZATION, "Bearer " + laptop))
                .andExpect(status().isOk());

        me(laptop).andExpect(status().isUnauthorized());
        me(phone).andExpect(status().isOk());
    }

    @Test
    void logout_needsAToken() throws Exception {
        mockMvc.perform(post("/api/auth/logout")).andExpect(status().isUnauthorized());
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private org.springframework.test.web.servlet.ResultActions me(String token) throws Exception {
        return mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }
}
