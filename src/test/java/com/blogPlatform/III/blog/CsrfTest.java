package com.blogPlatform.III.blog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// The /csrf-demo chain: a session cookie + CSRF on. Each test first loads the page like a browser, takes the
// hidden token out of the HTML and keeps the session, then posts the form.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CsrfTest {

    private static final Pattern HIDDEN_TOKEN = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");

    @Autowired MockMvc mockMvc;

    @Test
    void thePage_hasAFormWithAHiddenCsrfToken_andStartsASession() throws Exception {
        MvcResult page = mockMvc.perform(get("/csrf-demo"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string(containsString("<input type=\"hidden\" name=\"_csrf\"")))
                .andReturn();

        assertNotNull(page.getRequest().getSession(false), "the demo uses a server session");
    }

    @Test
    void theFormWithItsToken_works() throws Exception {
        Browser browser = openPage();

        mockMvc.perform(post("/csrf-demo/notes").session(browser.session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("text", "hello")
                        .param("_csrf", browser.token))
                .andExpect(status().isSeeOther())
                .andExpect(header().string(HttpHeaders.LOCATION, "/csrf-demo"));

        assertEquals(List.of("hello"), browser.session.getAttribute("csrfDemoNotes"));
        mockMvc.perform(get("/csrf-demo").session(browser.session))
                .andExpect(content().string(containsString("<li>hello</li>")));
    }

    @Test
    void theFormWithoutAToken_is403() throws Exception {
        Browser browser = openPage();

        // the session cookie is sent (like a forged form from another site would), but there is no token
        mockMvc.perform(post("/csrf-demo/notes").session(browser.session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("text", "sent by a forged form"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.message").value("Missing or invalid CSRF token"))
                .andExpect(jsonPath("$.path").value("/csrf-demo/notes"));

        assertNull(browser.session.getAttribute("csrfDemoNotes"), "nothing was saved");
    }

    @Test
    void aWrongToken_orTheTokenOfAnotherSession_is403() throws Exception {
        Browser victim = openPage();
        Browser attacker = openPage();

        mockMvc.perform(post("/csrf-demo/notes").session(victim.session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("text", "x").param("_csrf", "made-up-token"))
                .andExpect(status().isForbidden());

        // the attacker loads the page with their OWN session: that token is worthless in the victim's session
        mockMvc.perform(post("/csrf-demo/notes").session(victim.session)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("text", "x").param("_csrf", attacker.token))
                .andExpect(status().isForbidden());
    }

    @Test
    void notesAreShownAsText_notAsHtml() throws Exception {
        Browser browser = openPage();
        mockMvc.perform(post("/csrf-demo/notes").session(browser.session)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .param("text", "<script>alert(1)</script>")
                .param("_csrf", browser.token));

        mockMvc.perform(get("/csrf-demo").session(browser.session))
                .andExpect(content().string(containsString("&lt;script&gt;alert(1)&lt;/script&gt;")))
                .andExpect(content().string(not(containsString("<script>alert(1)"))));
    }

    @Test
    void theApi_staysStateless_andWithoutCsrf() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/posts")).andExpect(status().isOk()).andReturn();
        assertNull(result.getRequest().getSession(false), "the JWT API never creates a session");
    }

    // what a browser keeps after loading the page: its session (cookie) and the token from the HTML
    private record Browser(MockHttpSession session, String token) {
    }

    private Browser openPage() throws Exception {
        MvcResult page = mockMvc.perform(get("/csrf-demo")).andExpect(status().isOk()).andReturn();
        Matcher token = HIDDEN_TOKEN.matcher(page.getResponse().getContentAsString());
        assertTrue(token.find(), "the page must contain the hidden _csrf field");
        return new Browser((MockHttpSession) page.getRequest().getSession(false), token.group(1));
    }
}
