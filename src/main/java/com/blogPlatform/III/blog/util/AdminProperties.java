package com.blogPlatform.III.blog.util;

import org.springframework.boot.context.properties.ConfigurationProperties;

// app.admin.* from application.properties: the first admin account (AdminAccountInitializer)
@ConfigurationProperties("app.admin")
public record AdminProperties(String username, String email, String password) {

    // a record prints every field by default; the password must never end up in a log
    @Override
    public String toString() {
        return "AdminProperties[username=" + username + ", email=" + email + "]";
    }
}
