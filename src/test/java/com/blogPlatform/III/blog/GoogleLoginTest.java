package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.security.SecurityErrorHandler;
import com.blogPlatform.III.blog.security.oauth2.GoogleLoginHandler;
import com.blogPlatform.III.blog.security.oauth2.OAuth2Properties;
import com.blogPlatform.III.blog.service.AuthService;
import com.blogPlatform.III.blog.service.UserService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The real round trip needs a browser and Google. These tests check both ends of it:
// our redirect TO Google, and what our handler does with the Google user that comes BACK.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "google-test"})
@Transactional
class GoogleLoginTest {

    @Autowired MockMvc mockMvc;
    @Autowired GoogleLoginHandler googleLoginHandler;
    @Autowired UserService userService;
    @Autowired AuthService authService;
    @Autowired SecurityErrorHandler errorHandler;
    @Autowired ObjectMapper objectMapper;
    @Autowired UserRepository userRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void loginStart_sendsTheBrowserToGoogle() throws Exception {
        mockMvc.perform(get("/oauth2/authorization/google"))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string(HttpHeaders.LOCATION, allOf(
                        startsWith("https://accounts.google.com/o/oauth2/v2/auth"),
                        containsString("client_id=test-client-id"),
                        containsString("scope=openid%20profile%20email"),
                        // random value Google sends back; Spring compares it, against forged callbacks
                        containsString("state="),
                        containsString("redirect_uri=http://localhost/login/oauth2/code/google"))));
    }

    @Test
    void newGoogleUser_getsAReaderAccount_andOurOwnToken() throws Exception {
        MockHttpServletResponse response = succeed(googleUser("google-123", "maria.lopez@gmail.com", true));

        assertEquals(200, response.getStatus());
        String body = response.getContentAsString();
        assertEquals("Logged in with Google", JsonPath.read(body, "$.message"));
        assertEquals("maria.lopez", JsonPath.read(body, "$.data.username"));
        assertEquals(List.of("READER"), JsonPath.read(body, "$.data.roles"));
        assertTrue(userRepository.findByEmail("maria.lopez@gmail.com").isPresent());

        String token = JsonPath.read(body, "$.data.accessToken");
        mockMvc.perform(get("/api/auth/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("maria.lopez"))
                .andExpect(jsonPath("$.data.authProvider").value("GOOGLE"));
    }

    @Test
    void knownEmail_logsIntoTheExistingAccount() throws Exception {
        userService.register(new UserRequest("anna", "anna@test.com", "secret123"));

        MockHttpServletResponse response = succeed(googleUser("google-9", "anna@test.com", true));

        assertEquals("anna", JsonPath.read(response.getContentAsString(), "$.data.username"));
    }

    @Test
    void unverifiedEmail_isRefused() throws Exception {
        MockHttpServletResponse response = succeed(googleUser("google-123", "maria.lopez@gmail.com", false));

        assertEquals(401, response.getStatus());
        assertEquals("Google login failed: the Google account has no verified email",
                JsonPath.read(response.getContentAsString(), "$.message"));
        assertTrue(userRepository.findByEmail("maria.lopez@gmail.com").isEmpty());
    }

    @Test
    void withAFrontend_redirectsThere_withTheTokenInTheFragment() throws Exception {
        GoogleLoginHandler withFrontend = new GoogleLoginHandler(userService, authService, errorHandler, objectMapper,
                new OAuth2Properties("http://localhost:3000/login/done"));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.getSession(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        withFrontend.onAuthenticationSuccess(request, response, googleUser("google-123", "maria.lopez@gmail.com", true));

        assertEquals(302, response.getStatus());
        assertTrue(response.getRedirectedUrl().startsWith("http://localhost:3000/login/done#access_token=ey"));
        // the short-lived OAuth2 session is gone: the JWT replaces it
        assertNull(request.getSession(false));
    }

    @Test
    void failedGoogleLogin_is401_inTheApiShape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/login/oauth2/code/google");
        MockHttpServletResponse response = new MockHttpServletResponse();

        googleLoginHandler.onAuthenticationFailure(request, response,
                new OAuth2AuthenticationException(new OAuth2Error("access_denied"), "the user said no"));

        assertEquals(401, response.getStatus());
        assertEquals("Google login failed: the user said no", JsonPath.read(response.getContentAsString(), "$.message"));
        assertEquals("/login/oauth2/code/google", JsonPath.read(response.getContentAsString(), "$.path"));
    }

    private MockHttpServletResponse succeed(OAuth2AuthenticationToken google) throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        googleLoginHandler.onAuthenticationSuccess(new MockHttpServletRequest(), response, google);
        return response;
    }

    // what Spring Security hands our handler after Google: the user's attributes, named by "sub"
    private static OAuth2AuthenticationToken googleUser(String sub, String email, boolean verified) {
        var authorities = List.of(new SimpleGrantedAuthority("OIDC_USER"));
        var attributes = Map.<String, Object>of("sub", sub, "email", email, "email_verified", verified, "name", "Maria Lopez");
        return new OAuth2AuthenticationToken(new DefaultOAuth2User(authorities, attributes, "sub"), authorities, "google");
    }
}
