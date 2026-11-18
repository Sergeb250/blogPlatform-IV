package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.entity.RoleName;
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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    @Test
    void roleChange_revokesEveryTokenOfThatUser() throws Exception {
        String laptop = login("anna", "secret123");
        String phone = login("anna", "secret123");
        String admin = adminToken();

        mockMvc.perform(put("/api/users/" + annaId + "/roles").header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"roles\": [\"AUTHOR\"]}"))
                .andExpect(status().isOk());

        me(laptop).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(REVOKED));
        me(phone).andExpect(status().isUnauthorized());
        // the admin's own token is not touched
        me(admin).andExpect(status().isOk());
        // a new login carries the new role
        me(login("anna", "secret123")).andExpect(status().isOk()).andExpect(jsonPath("$.data.roles[0]").value("AUTHOR"));
    }

    @Test
    void passwordChange_logsOutEveryDevice() throws Exception {
        String token = login("anna", "secret123");

        mockMvc.perform(put("/api/users/" + annaId).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\": \"anna\", \"email\": \"anna@test.com\", \"password\": \"newSecret456\"}"))
                .andExpect(status().isOk());

        me(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(REVOKED));
        me(login("anna", "newSecret456")).andExpect(status().isOk());
    }

    @Test
    void deletedAccount_tokenStopsWorking() throws Exception {
        String token = login("anna", "secret123");

        mockMvc.perform(delete("/api/users/" + annaId).header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk());

        me(token).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.message").value(REVOKED));
    }

    @Test
    void admin_seesActiveSessions_withTheirUsage() throws Exception {
        String token = login("anna", "secret123");
        me(token);
        me(token);

        // found by the token's own id: the registry lives in memory and also holds tokens of earlier tests
        String session = "$.data[?(@.tokenId == '" + tokenId(token) + "')]";
        mockMvc.perform(get("/api/admin/security/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath(session + ".username").value("anna"))
                .andExpect(jsonPath(session + ".requests").value(2))
                .andExpect(jsonPath(session + ".lastIp").value("127.0.0.1"));
    }

    @Test
    void admin_logsAUserOutEverywhere() throws Exception {
        String laptop = login("anna", "secret123");
        String phone = login("anna", "secret123");

        mockMvc.perform(delete("/api/admin/security/users/" + annaId + "/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").value(2));

        me(laptop).andExpect(status().isUnauthorized());
        me(phone).andExpect(status().isUnauthorized());
    }

    @Test
    void sessionEndpoints_areForAdminsOnly() throws Exception {
        String token = login("anna", "secret123");

        mockMvc.perform(get("/api/admin/security/sessions").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/admin/security/users/" + annaId + "/sessions")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    // the "jti" claim, read from the token's payload (the middle part, base64url JSON)
    private static String tokenId(String token) {
        String payload = new String(Base64.getUrlDecoder().decode(token.split("\\.")[1]), StandardCharsets.UTF_8);
        return JsonPath.read(payload, "$.jti");
    }

    private String adminToken() throws Exception {
        Long bossId = userService.register(new UserRequest("boss", "boss@test.com", "secret123")).id();
        userService.updateRoles(bossId, Set.of(RoleName.ADMIN), null);
        return login("boss", "secret123");
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
