package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.service.UserService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the whole flow over HTTP: 5 wrong passwords -> 429 with Retry-After
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class BruteForceTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        // a username only this test uses: the attempt counter lives in memory, shared by all tests
        userService.register(new UserRequest("victim", "victim@test.com", "secret123"));
    }

    @Test
    void fiveWrongPasswords_thenEvenTheRightOneWaits() throws Exception {
        for (int i = 1; i <= 5; i++) {
            mockMvc.perform(login("victim", "guess" + i + "xx", "203.0.113.7")).andExpect(status().isUnauthorized());
        }

        mockMvc.perform(login("victim", "guess6xx", "203.0.113.7"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("\\d+")))
                .andExpect(jsonPath("$.status").value(429))
                .andExpect(jsonPath("$.message").value("Too many failed logins. Try again in 15 minute(s)"));

        // blocked before the password is checked: a lucky guess now would teach the attacker nothing
        mockMvc.perform(login("victim", "secret123", "203.0.113.7")).andExpect(status().isTooManyRequests());

        // the real owner, from their own network, is not locked out by the attacker
        mockMvc.perform(login("victim", "secret123", "198.51.100.20")).andExpect(status().isOk());
    }

    private static MockHttpServletRequestBuilder login(String username, String password, String ip) {
        return post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr(ip);
                    return request;
                })
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password));
    }
}
