package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.config.PasswordConfig;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.*;

class PasswordHashingTest {

    private final PasswordEncoder encoder = new PasswordConfig().passwordEncoder();

    @Test
    void hash_isNotThePlainPassword_andMatchesIt() {
        String hash = encoder.encode("secret123");

        assertNotEquals("secret123", hash);
        // $2a$ = BCrypt, 10 = cost factor (2^10 rounds), then 22 characters of salt and 31 of hash
        assertTrue(hash.startsWith("$2a$10$"));
        assertEquals(60, hash.length());
        assertTrue(encoder.matches("secret123", hash));
        assertFalse(encoder.matches("wrong-password1", hash));
    }

    @Test
    void samePassword_givesADifferentHashEveryTime() {
        String first = encoder.encode("secret123");
        String second = encoder.encode("secret123");

        assertNotEquals(first, second);
        assertTrue(encoder.matches("secret123", first));
        assertTrue(encoder.matches("secret123", second));
    }

    @Test
    void noStoredHash_neverMatches() {
        // a Google account has no password hash: a password login must fail, not crash
        assertFalse(encoder.matches("secret123", null));
    }
}
