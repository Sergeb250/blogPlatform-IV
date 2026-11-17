package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.jwt.JwtProperties;
import com.blogPlatform.III.blog.security.jwt.JwtService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class JwtAuthTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserService userService;
    @Autowired JwtService jwtService;
    @Autowired JwtProperties jwtProperties;
    @Autowired CacheManager cacheManager;

    Long annaId;

    @BeforeEach
    void setUp() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
        annaId = userService.register(new UserRequest("anna", "anna@test.com", "secret123")).id();
    }

    @Test
    void register_createsAReader() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "ben", "email": "ben@test.com", "password": "secret123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("User registered successfully"))
                .andExpect(jsonPath("$.data.roles.length()").value(1))
                .andExpect(jsonPath("$.data.roles[0]").value("READER"));
    }

    @Test
    void register_cannotChooseARole() throws Exception {
        mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "mallory", "email": "m@test.com", "password": "secret123", "role": "ADMIN"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.role").value("unknown field"));
    }

    @Test
    void login_returnsABearerToken() throws Exception {
        mockMvc.perform(login("anna", "secret123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged in successfully"))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresIn").value(3600))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andExpect(jsonPath("$.data.username").value("anna"))
                .andExpect(jsonPath("$.data.roles[0]").value("READER"))
                // header.payload.signature, each part base64url
                .andExpect(jsonPath("$.data.accessToken").value(matchesPattern("^[\\w-]+\\.[\\w-]+\\.[\\w-]+$")));
    }

    @Test
    void token_hasTheExpectedHeaderAndClaims() throws Exception {
        String[] parts = loginAndGetToken("anna", "secret123").split("\\.");
        String header = decode(parts[0]);
        String payload = decode(parts[1]);

        assertEquals("HS256", JsonPath.read(header, "$.alg"));

        assertEquals("anna", JsonPath.read(payload, "$.sub"));
        assertEquals(annaId, ((Number) JsonPath.read(payload, "$.uid")).longValue());
        assertEquals(List.of("READER"), JsonPath.read(payload, "$.roles"));
        assertEquals("blog-platform", JsonPath.read(payload, "$.iss"));
        assertNotNull(JsonPath.read(payload, "$.jti"));
        long issuedAt = ((Number) JsonPath.read(payload, "$.iat")).longValue();
        long expiresAt = ((Number) JsonPath.read(payload, "$.exp")).longValue();
        assertEquals(3600, expiresAt - issuedAt);

        assertFalse(payload.contains("$2a$"), "the password hash is never put in a token");
        // the signature is not data: it is the 32-byte HMAC-SHA256 of "header.payload", base64url without padding
        assertEquals(43, parts[2].length());
    }

    @Test
    void login_wrongPasswordAndUnknownUser_are401_withTheSameMessage() throws Exception {
        mockMvc.perform(login("anna", "wrong-password1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Invalid username or password"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(login("nobody", "secret123"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid username or password"));
    }

    @Test
    void login_withEmptyFields_is400() throws Exception {
        mockMvc.perform(login("", ""))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.username").exists())
                .andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    void me_withToken_returnsTheUserOfTheToken() throws Exception {
        String token = loginAndGetToken("anna", "secret123");

        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(annaId))
                .andExpect(jsonPath("$.data.username").value("anna"))
                .andExpect(jsonPath("$.data.roles[0]").value("READER"));
    }

    @Test
    void me_withoutToken_is401() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(
                        "Authentication required: log in and send 'Authorization: Bearer <token>'"));
    }

    @Test
    void token_opensProtectedEndpoints() throws Exception {
        String token = loginAndGetToken("anna", "secret123");

        mockMvc.perform(get("/api/stats")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/stats").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void expiredToken_is401() throws Exception {
        // same secret, but the token's lifetime already ended 5 minutes ago
        JwtService pastService = new JwtService(
                new JwtProperties(jwtProperties.secret(), Duration.ofMinutes(-5), jwtProperties.issuer()));
        String expired = pastService.issue(anna()).value();

        expectRejected(expired, "Token has expired, please log in again");
    }

    @Test
    void changedPayload_is401() throws Exception {
        // a READER edits the payload by hand to become ADMIN, and keeps the old signature
        String[] parts = loginAndGetToken("anna", "secret123").split("\\.");
        String forgedPayload = encode(decode(parts[1]).replace("\"READER\"", "\"ADMIN\""));
        String forged = parts[0] + "." + forgedPayload + "." + parts[2];

        expectRejected(forged, "Token signature is not valid");
    }

    @Test
    void tokenSignedWithAnotherSecret_is401() throws Exception {
        byte[] otherKey = new byte[32];
        new SecureRandom().nextBytes(otherKey);
        JwtService attacker = new JwtService(new JwtProperties(
                Base64.getEncoder().encodeToString(otherKey), Duration.ofHours(1), jwtProperties.issuer()));

        expectRejected(attacker.issue(anna()).value(), "Token signature is not valid");
    }

    @Test
    void unsignedToken_is401() throws Exception {
        // the classic "alg: none" trick: no signature at all
        String unsigned = encode("{\"alg\":\"none\"}") + "."
                + encode("{\"sub\":\"anna\",\"uid\":" + annaId + ",\"roles\":[\"ADMIN\"],\"iss\":\"blog-platform\"}") + ".";

        expectRejected(unsigned, "Unsigned tokens are not accepted");
    }

    @Test
    void tokenFromAnotherIssuer_is401() throws Exception {
        JwtService otherApp = new JwtService(
                new JwtProperties(jwtProperties.secret(), Duration.ofHours(1), "another-app"));

        expectRejected(otherApp.issue(anna()).value(), "Token is malformed or not valid");
    }

    @Test
    void garbageToken_is401() throws Exception {
        expectRejected("not-a-token", "Token is malformed or not valid");
    }

    @Test
    void badToken_isRefused_evenOnAPublicEndpoint() throws Exception {
        // a broken token is an error the client must fix, not a silent "continue as anonymous"
        mockMvc.perform(get("/api/posts").header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token"))
                .andExpect(status().isUnauthorized());

        // another scheme (not "Bearer ") is simply not ours: the request stays anonymous
        mockMvc.perform(get("/api/posts").header(HttpHeaders.AUTHORIZATION, "Basic YW5uYTpzZWNyZXQ="))
                .andExpect(status().isOk());
    }

    @Test
    void jwtService_keepsIdUsernameAndEveryRole() {
        AuthUser boss = new AuthUser(7L, "boss", "$2a$10$hash", EnumSet.of(RoleName.ADMIN, RoleName.AUTHOR));

        AuthUser back = jwtService.toAuthUser(jwtService.verify(jwtService.issue(boss).value()));

        assertEquals(7L, back.id());
        assertEquals("boss", back.username());
        assertEquals(Set.of(RoleName.ADMIN, RoleName.AUTHOR), back.roles());
        assertNull(back.passwordHash());
    }

    private MockHttpServletRequestBuilder login(String username, String password) {
        return post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\": \"%s\", \"password\": \"%s\"}".formatted(username, password));
    }

    private String loginAndGetToken(String username, String password) throws Exception {
        String body = mockMvc.perform(login(username, password))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.accessToken");
    }

    private void expectRejected(String token, String reason) throws Exception {
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(reason))
                .andExpect(jsonPath("$.path").value("/api/auth/me"));
    }

    private AuthUser anna() {
        return new AuthUser(annaId, "anna", null, EnumSet.of(RoleName.READER));
    }

    private static String decode(String base64Url) {
        return new String(Base64.getUrlDecoder().decode(base64Url), StandardCharsets.UTF_8);
    }

    private static String encode(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
