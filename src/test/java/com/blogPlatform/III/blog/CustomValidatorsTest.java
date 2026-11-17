package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.PostRequest;
import com.blogPlatform.III.blog.dto.request.TagRequest;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.validation.CursorValidator;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class CustomValidatorsTest {

    private static final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void username_lengthAndCharacters() {
        assertFalse(errors(user("serge_k.1")).containsKey("username"));

        assertEquals("must be between 3 and 50 characters", errors(user("ab")).get("username"));
        assertEquals("must be between 3 and 50 characters", errors(user("a".repeat(51))).get("username"));
        assertEquals("may contain only letters, digits, '.', '_' and '-'", errors(user("bad name!")).get("username"));
    }

    @Test
    void tagName_lengthAndCharacters() {
        for (String ok : List.of("java", "spring boot", "c++", "c#", "node.js", "café")) {
            assertTrue(errors(new TagRequest(ok)).isEmpty(), ok + " should be valid");
        }

        assertEquals("must be at most 50 characters", errors(new TagRequest("x".repeat(51))).get("name"));
        assertEquals("must start with a letter or digit and may contain only letters, digits, spaces and . _ + # -",
                errors(new TagRequest("-java")).get("name"));
        assertTrue(errors(new TagRequest("<script>")).containsKey("name"));
    }

    @Test
    void tagName_insideAList_reportsTheBadElement() {
        PostRequest post = new PostRequest(1L, "Title", "Content", null, List.of("java", "<b>"));

        Map<String, String> errors = errors(post);

        assertEquals(1, errors.size());
        assertTrue(errors.keySet().iterator().next().startsWith("tags[1]"), "the 2nd tag is the bad one");
    }

    @Test
    void cursor_mustBeARealNextCursor() {
        CursorValidator cursorValidator = new CursorValidator();

        assertTrue(cursorValidator.isValid(null, null));
        assertTrue(cursorValidator.isValid("", null));
        assertTrue(cursorValidator.isValid("2025-03-13T09:22,16911", null));
        assertTrue(cursorValidator.isValid("2025-03-13T09:22:05.123456,7", null));

        assertFalse(cursorValidator.isValid("not-a-cursor", null));
        assertFalse(cursorValidator.isValid("2025-13-45T10:00,5", null));
        assertFalse(cursorValidator.isValid("2025-03-13T09:22,-5", null));
        assertFalse(cursorValidator.isValid("2025-03-13T09:22", null));
    }

    private static UserRequest user(String username) {
        return new UserRequest(username, "serge@test.com", "secret123");
    }

    private static Map<String, String> errors(Object request) {
        return validator.validate(request).stream()
                .collect(Collectors.toMap(v -> v.getPropertyPath().toString(), v -> v.getMessage(), (a, b) -> a + "; " + b));
    }
}
