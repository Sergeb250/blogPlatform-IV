package com.blogPlatform.III.blog.security;

import com.blogPlatform.III.blog.entity.RoleName;
import com.blogPlatform.III.blog.entity.User;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;

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

    @Override
    public String toString() {
        return "AuthUser[id=" + id + ", username=" + username + ", roles=" + roles + "]";
    }
}
