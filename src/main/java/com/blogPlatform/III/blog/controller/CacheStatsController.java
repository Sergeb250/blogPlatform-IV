package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.CacheStatsResponse;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCache;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Cache", description = "How well the in-memory caches work")
@RestController
@RequestMapping("/api/cache-stats")
@RequiredArgsConstructor
public class CacheStatsController {

    private final CacheManager cacheManager;

    @Operation(summary = "Cache statistics (ADMIN)",
            description = "Size, hits, misses, hit rate and evictions of every cache. 403: not an admin.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CacheStatsResponse>>> getStats() {
        List<CacheStatsResponse> stats = cacheManager.getCacheNames().stream()
                .sorted()
                .map(name -> {
                    CaffeineCache cache = (CaffeineCache) cacheManager.getCache(name);
                    CacheStats numbers = cache.getNativeCache().stats();
                    return new CacheStatsResponse(name, cache.getNativeCache().estimatedSize(),
                            numbers.hitCount(), numbers.missCount(),
                            Math.round(numbers.hitRate() * 1000) / 1000.0, numbers.evictionCount());
                })
                .toList();
        return ApiResponse.ok("Cache statistics retrieved successfully", stats);
    }
}
