package com.blogPlatform.III.blog.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

// app.cors.* from application.properties: the browser origins (frontends) that may call the API
@ConfigurationProperties("app.cors")
public record CorsProperties(List<String> allowedOrigins) {
}
