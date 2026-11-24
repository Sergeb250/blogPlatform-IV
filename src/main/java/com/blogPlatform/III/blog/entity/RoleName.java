package com.blogPlatform.III.blog.entity;

public enum RoleName {
    ADMIN,
    AUTHOR,
    READER;

    public String authority() {
        return "ROLE_" + name();
    }
}
