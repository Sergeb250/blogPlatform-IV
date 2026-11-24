package com.blogPlatform.III.blog.controller;

import io.swagger.v3.oas.annotations.Hidden;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import static org.springframework.web.util.HtmlUtils.htmlEscape;

// A small HTML page with a session cookie, to show CSRF protection (SecurityConfig.csrfDemoSecurity).
// Not part of the JSON API, so it is hidden from Swagger.
@Hidden
@Controller
@RequestMapping("/csrf-demo")
public class CsrfDemoController {

    static final String NOTES = "csrfDemoNotes";
    private static final int MAX_NOTES = 20;

    // filled in by Spring Security: the value it expects back in the "_csrf" form field
    @GetMapping(produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String page(CsrfToken csrf, HttpSession session) {
        StringBuilder list = new StringBuilder();
        for (String note : notes(session)) {
            // escaped: a note like <script> must show as text, not run
            list.append("<li>").append(htmlEscape(note)).append("</li>");
        }
        return PAGE
                .replace("{csrfField}", htmlEscape(csrf.getParameterName()))
                .replace("{csrfToken}", htmlEscape(csrf.getToken()))
                .replace("{notes}", list.isEmpty() ? "<p>No notes yet.</p>" : "<ul>" + list + "</ul>");
    }

    // only reached with a valid token: the CSRF filter answers 403 before this method runs
    @PostMapping(value = "/notes", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<Void> addNote(@RequestParam @NotBlank @Size(max = 200) String text, HttpSession session) {
        List<String> notes = notes(session);
        notes.add(text);
        if (notes.size() > MAX_NOTES) {
            notes.removeFirst();
        }
        session.setAttribute(NOTES, notes);
        // Post/Redirect/Get: a browser refresh shows the page again instead of sending the form twice
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/csrf-demo")).build();
    }

    @SuppressWarnings("unchecked")
    private static List<String> notes(HttpSession session) {
        return session.getAttribute(NOTES) instanceof List<?> notes ? (List<String>) notes : new ArrayList<>();
    }

    private static final String PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>CSRF demo</title>
              <style>body{font-family:sans-serif;max-width:42rem;margin:2rem auto;line-height:1.5}form{margin:.5rem 0 1.5rem}</style>
            </head>
            <body>
              <h1>CSRF demo</h1>
              <p>This page keeps your notes in a server session. Your browser sends the session cookie
                 (<code>JSESSIONID</code>) with every form below, on its own.</p>

              <h2>1. Form with the CSRF token</h2>
              <form method="post" action="/csrf-demo/notes">
                <input type="hidden" name="{csrfField}" value="{csrfToken}">
                <input name="text" maxlength="200" required placeholder="Write a note">
                <button>Save note</button>
              </form>

              <h2>2. The same form without the token</h2>
              <p>This is what a foreign page can send: it can make your browser post a form (with your cookie),
                 but it can't read this page, so it can't know the token.</p>
              <form method="post" action="/csrf-demo/notes">
                <input name="text" value="sent by a forged form">
                <button>Send without token</button>
              </form>
              <p>Result: <strong>403 Missing or invalid CSRF token</strong>.</p>

              <h2>Notes in your session</h2>
              {notes}
            </body>
            </html>
            """;
}
