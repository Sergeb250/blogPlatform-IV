package com.blogPlatform.III.blog.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Duration;

@Configuration
// cache wraps the transaction: a hit opens no transaction, put/evict run after the commit
@EnableCaching(order = Ordered.HIGHEST_PRECEDENCE)
public class CacheConfig {

    public static final String POSTS = "posts";
    public static final String USERS = "users";
    public static final String TAGS = "tags";
    public static final String POPULAR_POSTS = "popularPosts";
    public static final String TOP_RATED_POSTS = "topRatedPosts";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();

        manager.registerCustomCache(POSTS,           build(10_000, Duration.ofMinutes(10)));
        manager.registerCustomCache(USERS,           build(5_000,  Duration.ofMinutes(30)));
        manager.registerCustomCache(TAGS,            build(100,    Duration.ofHours(1)));
        manager.registerCustomCache(POPULAR_POSTS,   build(20,     Duration.ofSeconds(60)));
        manager.registerCustomCache(TOP_RATED_POSTS, build(50,     Duration.ofSeconds(60)));

        return manager;
    }

    private static Cache<Object, Object> build(long maxEntries, Duration timeToLive) {
        return Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(timeToLive)
                .recordStats()
                .build();
    }
}
