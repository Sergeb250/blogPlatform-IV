package com.blogPlatform.III.blog.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

// Which web pages (origins) may call this API from JavaScript in a browser.
// Only browsers apply CORS: Postman, curl or another server are not limited by it.
@Configuration
public class CorsConfig {

    @Bean
    public CorsConfigurationSource corsConfigurationSource(CorsProperties properties) {
        CorsConfiguration cors = new CorsConfiguration();
        // exact origins, never "*": a page on any other origin gets 403
        cors.setAllowedOrigins(properties.allowedOrigins());
        cors.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE"));
        cors.setAllowedHeaders(List.of(HttpHeaders.AUTHORIZATION, HttpHeaders.CONTENT_TYPE));
        // a browser hides response headers from JavaScript unless they are listed: the 429 "wait N seconds"
        cors.setExposedHeaders(List.of(HttpHeaders.RETRY_AFTER));
        // the token travels in the Authorization header, not in a cookie: the browser sends no cookies cross-origin
        cors.setAllowCredentials(false);
        // the browser may reuse a preflight answer for an hour instead of asking before every request
        cors.setMaxAge(Duration.ofHours(1));

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", cors);
        return source;
    }
}
