package com.blogPlatform.III.blog;

import com.blogPlatform.III.blog.dto.request.LoginRequest;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.entity.AuthProvider;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.service.AuthService;
import com.blogPlatform.III.blog.service.UserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class GoogleUserTest {

    @Autowired UserService userService;
    @Autowired AuthService authService;
    @Autowired UserRepository userRepository;
    @Autowired CacheManager cacheManager;

    @BeforeEach
    void clearCaches() {
        cacheManager.getCacheNames().forEach(name -> cacheManager.getCache(name).clear());
    }

    @Test
    void newEmail_createsAGoogleReader_withoutPassword() {
        AuthUser maria = userService.findOrCreateGoogleUser("google-123", "maria.lopez@gmail.com");

        assertEquals("maria.lopez", maria.username());
        assertEquals(Set.of(RoleName.READER), maria.roles());

        User stored = userRepository.findByEmail("maria.lopez@gmail.com").orElseThrow();
        assertEquals(AuthProvider.GOOGLE, stored.getAuthProvider());
        assertEquals("google-123", stored.getProviderId());
        assertNull(stored.getPasswordHash());
    }

    @Test
    void sameGoogleUserTwice_isOneAccount() {
        AuthUser first = userService.findOrCreateGoogleUser("google-123", "maria.lopez@gmail.com");
        long users = userRepository.count();

        AuthUser second = userService.findOrCreateGoogleUser("google-123", "maria.lopez@gmail.com");

        assertEquals(first.id(), second.id());
        assertEquals(users, userRepository.count());
    }

    @Test
    void knownEmail_logsIntoTheExistingPasswordAccount() {
        Long annaId = userService.register(new UserRequest("anna", "anna@test.com", "secret123")).id();
        long users = userRepository.count();

        AuthUser viaGoogle = userService.findOrCreateGoogleUser("google-9", "anna@test.com");

        assertEquals(annaId, viaGoogle.id());
        assertEquals("anna", viaGoogle.username());
        assertEquals(users, userRepository.count());
    }

    @Test
    void takenUsername_getsANumber() {
        userService.register(new UserRequest("maria", "maria@other.com", "secret123"));

        assertEquals("maria-2", userService.findOrCreateGoogleUser("g-1", "maria@gmail.com").username());
        assertEquals("maria-3", userService.findOrCreateGoogleUser("g-2", "maria@yahoo.com").username());
    }

    @Test
    void username_keepsOnlyAllowedCharacters() {
        assertEquals("jonews", userService.findOrCreateGoogleUser("g-1", "jo+news@gmail.com").username());
        // too short for a username (3+ characters)
        assertEquals("user-al", userService.findOrCreateGoogleUser("g-2", "al@gmail.com").username());
    }

    @Test
    void googleAccount_cannotLogInWithAPassword() {
        userService.findOrCreateGoogleUser("google-123", "maria.lopez@gmail.com");

        assertThrows(BadCredentialsException.class,
                () -> authService.login(new LoginRequest("maria.lopez", "anything123")));
    }

    @Test
    void googleUser_getsOurOwnToken() {
        AuthUser maria = userService.findOrCreateGoogleUser("google-123", "maria.lopez@gmail.com");

        var token = authService.issueToken(maria);

        assertEquals("Bearer", token.tokenType());
        assertEquals("maria.lopez", token.username());
        assertEquals(Set.of(RoleName.READER), token.roles());
    }
}
