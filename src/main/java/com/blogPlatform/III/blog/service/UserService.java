package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.UserRequest;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.UserResponse;
import com.blogPlatform.III.blog.entity.AuthProvider;
import com.blogPlatform.III.blog.entity.Role;
import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.DuplicateResourceException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.RoleRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.security.AuthUser;
import com.blogPlatform.III.blog.security.token.ActiveSessionRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final ActiveSessionRegistry sessions;

    @Transactional
    public UserResponse register(UserRequest request) {
        checkUsernameFree(request.username());
        checkEmailFree(request.email());

        User user = new User();
        copyRequestToUser(request, user);
        user.getRoles().add(findRole(RoleName.READER));
        return UserResponse.from(userRepository.save(user));
    }

    // Google has verified the email, so a known email logs into that account; an unknown one gets a new READER
    @Transactional
    public AuthUser findOrCreateGoogleUser(String googleId, String email) {
        User user = userRepository.findByEmail(email).orElseGet(() -> createGoogleUser(googleId, email));
        return AuthUser.from(user);
    }

    public PageResponse<UserResponse> getAll(Pageable pageable) {
        return PageResponse.from(userRepository.findAll(pageable).map(UserResponse::from));
    }

    @Cacheable(cacheNames = CacheConfig.USERS, key = "#id")
    public UserResponse getById(Long id) {
        return UserResponse.from(userRepository.findOrThrow(id));
    }

    public UserResponse getByUsername(String username) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User '" + username + "' not found"));
        return UserResponse.from(user);
    }

    @Caching(
            put = @CachePut(cacheNames = CacheConfig.USERS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfig.POSTS, allEntries = true))
    @Transactional
    public UserResponse update(Long id, UserRequest request) {
        User user = userRepository.findOrThrow(id);

        if (!user.getUsername().equals(request.username())) {
            checkUsernameFree(request.username());
        }
        if (!user.getEmail().equals(request.email())) {
            checkEmailFree(request.email());
        }

        copyRequestToUser(request, user);
        UserResponse updated = UserResponse.from(userRepository.save(user));
        sessions.revokeAll(id, "PROFILE_CHANGE");
        return updated;
    }

    // tokens carry the roles they were issued with, so they are revoked: the next login has the new roles
    @CachePut(cacheNames = CacheConfig.USERS, key = "#id")
    @Transactional
    public UserResponse updateRoles(Long id, Set<RoleName> roles, Long actingUserId) {
        User user = userRepository.findOrThrow(id);
        // an admin who drops their own ADMIN role could leave the platform without any admin
        if (id.equals(actingUserId) && !roles.contains(RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot remove the ADMIN role from your own account");
        }
        user.getRoles().clear();
        user.getRoles().addAll(roleRepository.findByNameIn(roles));
        UserResponse updated = UserResponse.from(userRepository.save(user));
        sessions.revokeAll(id, "ROLE_CHANGE");
        return updated;
    }

    @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
    @Transactional
    public void delete(Long id) {
        User user = userRepository.findOrThrow(id);
        userRepository.delete(user);
        sessions.revokeAll(id, "ACCOUNT_DELETED");
    }

    private User createGoogleUser(String googleId, String email) {
        User user = new User();
        user.setUsername(freeUsername(email));
        user.setEmail(email);
        user.setAuthProvider(AuthProvider.GOOGLE);
        user.setProviderId(googleId);
        user.getRoles().add(findRole(RoleName.READER));
        return userRepository.save(user);
    }

    // maria.lopez@gmail.com -> "maria.lopez"; if taken -> "maria.lopez-2", "maria.lopez-3", ...
    private String freeUsername(String email) {
        String base = email.substring(0, email.indexOf('@')).replaceAll("[^A-Za-z0-9._-]", "");
        if (base.length() < 3) {
            base = "user-" + base;
        }
        base = base.substring(0, Math.min(base.length(), 40));

        String candidate = base;
        for (int number = 2; userRepository.existsByUsername(candidate); number++) {
            candidate = base + "-" + number;
        }
        return candidate;
    }

    private void checkUsernameFree(String username) {
        if (userRepository.existsByUsername(username)) {
            throw new DuplicateResourceException("Username '" + username + "' is already taken");
        }
    }

    private void checkEmailFree(String email) {
        if (userRepository.existsByEmail(email)) {
            throw new DuplicateResourceException("Email '" + email + "' is already used");
        }
    }

    private void copyRequestToUser(UserRequest request, User user) {
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
    }

    private Role findRole(RoleName name) {
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role " + name + " is missing in the roles table"));
    }
}
