package com.blogPlatform.III.blog.entity;

public enum RoleName {
    ADMIN,
    AUTHOR,
    READER;

    // Spring's hasRole('ADMIN') looks for the authority "ROLE_ADMIN"
    public String authority() {
        return "ROLE_" + name();
    }
}
