package com.blogPlatform.III.blog.security.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oauth2")
public record OAuth2Properties(String redirectUri) {

    public boolean hasRedirect() {
        return redirectUri != null && !redirectUri.isBlank();
    }
}
