package com.blogPlatform.III.blog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class CorsTest {

    private static final String FRONTEND = "http://localhost:3000";
    private static final String EVIL = "http://evil.example";

    @Autowired MockMvc mockMvc;

    @Test
    void preflight_fromTheFrontend_isAllowed() throws Exception {
        mockMvc.perform(preflight(FRONTEND, "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, containsString("POST")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, containsString("Authorization")))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void preflight_fromAnUnknownOrigin_is403() throws Exception {
        mockMvc.perform(preflight(EVIL, "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void preflight_forAMethodOrHeaderThatIsNotAllowed_is403() throws Exception {
        mockMvc.perform(preflight(FRONTEND, "TRACE")).andExpect(status().isForbidden());
        mockMvc.perform(options("/api/posts")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "X-Secret"))
                .andExpect(status().isForbidden());
    }

    @Test
    void preflight_needsNoToken_onAProtectedEndpoint() throws Exception {
        // the browser never sends the token with a preflight, so it must not get a 401
        mockMvc.perform(options("/api/users")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                        .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }

    @Test
    void realRequest_fromTheFrontend_getsTheCorsHeader_fromAnUnknownOrigin_is403() throws Exception {
        mockMvc.perform(get("/api/posts").header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));

        mockMvc.perform(get("/api/posts").header(HttpHeaders.ORIGIN, EVIL))
                .andExpect(status().isForbidden());
    }

    @Test
    void errorsReachTheFrontend_too() throws Exception {
        // a 401 still carries the CORS header, so the page's JavaScript can read the JSON error
        mockMvc.perform(get("/api/users").header(HttpHeaders.ORIGIN, FRONTEND))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, FRONTEND));
    }

    @Test
    void noOriginHeader_isNotACorsRequest() throws Exception {
        // Postman, curl, another server: no Origin header, CORS does not apply
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void theApi_needsNoCsrfToken() throws Exception {
        // CSRF is off for the JWT API: a POST from the frontend works without any CSRF token,
        // and no session cookie is created
        mockMvc.perform(post("/api/auth/register")
                        .header(HttpHeaders.ORIGIN, FRONTEND)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "corsuser", "email": "cors@test.com", "password": "secret123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().doesNotExist(HttpHeaders.SET_COOKIE));
    }

    private MockHttpServletRequestBuilder preflight(String origin, String method) {
        return options("/api/posts")
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization, Content-Type");
    }
}
