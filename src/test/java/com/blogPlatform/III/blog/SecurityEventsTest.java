package com.blogPlatform.III.blog;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.service.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the real app: each security event arrives in the SECURITY log, and the admin report shows the attack
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SecurityEventsTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired CacheManager cacheManager;

    final Logger securityLog = (Logger) LoggerFactory.getLogger("SECURITY");
    final ListAppender<ILoggingEvent> events = new ListAppender<>();

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        events.start();
        securityLog.addAppender(events);
        // names and IPs only this class uses: the counters live in memory, shared by all tests
        userService.register(new UserRequest("ev_reader", "ev_reader@test.com", "secret123"));
        Long bossId = userService.register(new UserRequest("ev_admin", "ev_admin@test.com", "secret123")).id();
        userService.updateRoles(bossId, Set.of(RoleName.ADMIN), null);
    }

    @AfterEach
    void stopCapture() {
        securityLog.detachAppender(events);
    }

    @Test
    void loginsAndRejectedTokens_areLogged() throws Exception {
        mockMvc.perform(login("ev_reader", "wrong-pass1", "198.51.100.1")).andExpect(status().isUnauthorized());
        mockMvc.perform(login("ev_reader", "secret123", "198.51.100.1")).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        assertLogged("LOGIN_FAILURE user=ev_reader ip=198.51.100.1");
        assertLogged("LOGIN_SUCCESS user=ev_reader ip=198.51.100.1 method=PASSWORD");
        assertLogged("TOKEN_REJECTED ip=127.0.0.1 method=GET path=/api/auth/me reason=\"Token is malformed or not valid\"");
    }

    @Test
    void deniedAccess_isLogged_withTheUser() throws Exception {
        String reader = token("ev_reader");

        // a URL rule (/api/admin/**) and a @PreAuthorize rule (audit logs): both are logged the same way
        mockMvc.perform(get("/api/admin/security/report").header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/audit-logs").header(HttpHeaders.AUTHORIZATION, "Bearer " + reader))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());

        assertLogged("ACCESS_DENIED user=ev_reader ip=127.0.0.1 method=GET path=/api/admin/security/report");
        assertLogged("ACCESS_DENIED user=ev_reader ip=127.0.0.1 method=GET path=/api/audit-logs");
        assertLogged("ACCESS_UNAUTHENTICATED ip=127.0.0.1 method=GET path=/api/stats");
    }

    @Test
    void theReport_namesTheAttackedUserAndTheAttackingIp() throws Exception {
        for (int i = 1; i <= 6; i++) {
            mockMvc.perform(login("ev_reader", "guess" + i + "xx", "203.0.113.66"));
        }

        mockMvc.perform(get("/api/admin/security/report").header(HttpHeaders.AUTHORIZATION, "Bearer " + token("ev_admin")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.suspectedBruteForce[?(@.name == 'user ev_reader')].count").value(5))
                .andExpect(jsonPath("$.data.suspectedBruteForce[?(@.name == 'ip 203.0.113.66')].count").value(5))
                .andExpect(jsonPath("$.data.blockedNow").value(org.hamcrest.Matchers.hasItem("ev_reader|203.0.113.66")))
                .andExpect(jsonPath("$.data.blockedLogins").isNumber())
                .andExpect(jsonPath("$.data.topEndpoints").isArray());
        assertLogged("LOGIN_BLOCKED user=ev_reader ip=203.0.113.66");
    }

    private void assertLogged(String start) {
        List<String> lines = events.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertTrue(lines.stream().anyMatch(line -> line.startsWith(start)), "no log line starting with: " + start
                + "\nlogged: " + lines);
    }

    private String token(String username) throws Exception {
        String body = mockMvc.perform(login(username, "secret123", "127.0.0.1"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
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
