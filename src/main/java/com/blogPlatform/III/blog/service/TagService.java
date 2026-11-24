package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.TagRequest;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.dto.response.TagResponse;
import com.blogPlatform.III.blog.dto.response.TagUsageView;
import com.blogPlatform.III.blog.entity.Tag;
import com.blogPlatform.III.blog.exception.DuplicateResourceException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TagService {

    private final TagRepository tagRepository;

    @CacheEvict(cacheNames = CacheConfig.TAGS, allEntries = true)
    @Transactional
    public TagResponse create(TagRequest request) {
        String name = normalize(request.name());
        if (tagRepository.existsByName(name)) {
            throw new DuplicateResourceException("Tag '" + name + "' already exists");
        }
        Tag tag = new Tag();
        tag.setName(name);
        return TagResponse.from(tagRepository.save(tag));
    }

    @Cacheable(cacheNames = CacheConfig.TAGS,
            key = "#pageable.pageNumber + '-' + #pageable.pageSize + '-' + #pageable.sort")
    public PageResponse<TagResponse> getAll(Pageable pageable) {
        return PageResponse.from(tagRepository.findAll(pageable).map(TagResponse::from));
    }

    public TagResponse getById(Long id) {
        Tag tag = tagRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Tag " + id + " not found"));
        return TagResponse.from(tag);
    }

    public PageResponse<TagUsageView> getUsage(Pageable pageable) {
        return PageResponse.from(tagRepository.findTagUsage(pageable));
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.TAGS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.POSTS, allEntries = true)})
    @Transactional
    public void delete(Long id) {
        if (!tagRepository.existsById(id)) {
            throw new ResourceNotFoundException("Tag " + id + " not found");
        }
        tagRepository.deleteById(id);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Set<Tag> findOrCreate(List<String> names) {
        Set<Tag> tags = new HashSet<>();
        for (String rawName : names) {
            String name = normalize(rawName);
            Tag tag = tagRepository.findByName(name).orElseGet(() -> {
                Tag newTag = new Tag();
                newTag.setName(name);
                return tagRepository.save(newTag);
            });
            tags.add(tag);
        }
        return tags;
    }

    // tag names are kept in lowercase, so "Java" and "java" are one tag
    public static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
