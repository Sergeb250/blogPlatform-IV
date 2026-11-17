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

    @Transactional
    public UserResponse register(UserRequest request) {
        if (userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is already taken");
        }
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email '" + request.email() + "' is already used");
        }

        User user = new User();
        copyRequestToUser(request, user);
        // every new account starts as a reader: nobody can sign up as an author or admin
        user.getRoles().add(findRole(RoleName.READER));
        return UserResponse.from(userRepository.save(user));
    }

    // After a Google login. A known email logs into that account (Google has verified the address, so it is
    // the same person); an unknown one gets a new READER account without a password.
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
        return UserResponse.from(findUser(id));
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
        User user = findUser(id);

        if (!user.getUsername().equals(request.username())
                && userRepository.existsByUsername(request.username())) {
            throw new DuplicateResourceException("Username '" + request.username() + "' is already taken");
        }
        if (!user.getEmail().equals(request.email())
                && userRepository.existsByEmail(request.email())) {
            throw new DuplicateResourceException("Email '" + request.email() + "' is already used");
        }

        copyRequestToUser(request, user);
        return UserResponse.from(userRepository.save(user));
    }

    // replaces all roles of the user. The new roles reach the API with the user's NEXT token:
    // a token already issued keeps the roles it was signed with until it expires
    @CachePut(cacheNames = CacheConfig.USERS, key = "#id")
    @Transactional
    public UserResponse updateRoles(Long id, Set<RoleName> roles, Long actingUserId) {
        User user = findUser(id);
        // an admin who drops their own ADMIN role could leave the platform without any admin
        if (id.equals(actingUserId) && !roles.contains(RoleName.ADMIN)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "You cannot remove the ADMIN role from your own account");
        }
        user.getRoles().clear();
        user.getRoles().addAll(roleRepository.findByNameIn(roles));
        return UserResponse.from(userRepository.save(user));
    }

    @CacheEvict(cacheNames = CacheConfig.USERS, key = "#id")
    @Transactional
    public void delete(Long id) {
        User user = findUser(id);
        // only this row: posts, comments and reviews keep their FKs and stay live.
        // @SQLDelete on User: UPDATE users SET deleted_at = NOW(6) WHERE user_id = ?
        userRepository.delete(user);
    }

    private User findUser(Long id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("User " + id + " not found"));
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
    // (only the characters a username may have: letters, digits, . _ -)
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

    private void copyRequestToUser(UserRequest request, User user) {
        user.setUsername(request.username());
        user.setEmail(request.email());
        user.setPasswordHash(passwordEncoder.encode(request.password()));
    }

    private Role findRole(RoleName name) {
        // the three roles are inserted at startup (RoleSchemaInitializer), so a miss means a broken database
        return roleRepository.findByName(name)
                .orElseThrow(() -> new IllegalStateException("Role " + name + " is missing in the roles table"));
    }
}
