package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

// The logged-in user as Spring Security sees it: a small, read-only copy of the User entity.
// Not the entity itself, so no lazy loading or database session is needed after login.
public record AuthUser(Long id, String username, String passwordHash, Set<RoleName> roles) implements UserDetails {

    public static AuthUser from(User user) {
        return new AuthUser(user.getId(), user.getUsername(), user.getPasswordHash(), user.roleNames());
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream().map(role -> new SimpleGrantedAuthority(role.authority())).toList();
    }

    @Override
    public String getPassword() {
        return passwordHash;
    }

    @Override
    public String getUsername() {
        return username;
    }

    // a record prints every field by default; the password hash must never end up in a log
    @Override
    public String toString() {
        return "AuthUser[id=" + id + ", username=" + username + ", roles=" + roles + "]";
    }
}
