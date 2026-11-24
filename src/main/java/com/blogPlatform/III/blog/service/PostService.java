package com.blogPlatform.III.blog.service;

import com.blogPlatform.III.blog.config.CacheConfig;
import com.blogPlatform.III.blog.dto.request.*;
import com.blogPlatform.III.blog.dto.response.*;
import com.blogPlatform.III.blog.entity.Post;
import com.blogPlatform.III.blog.entity.PostStatus;
import com.blogPlatform.III.blog.entity.User;
import com.blogPlatform.III.blog.exception.ContentRejectedException;
import com.blogPlatform.III.blog.exception.DuplicateResourceException;
import com.blogPlatform.III.blog.exception.ResourceNotFoundException;
import com.blogPlatform.III.blog.repository.CommentRepository;
import com.blogPlatform.III.blog.repository.PostRepository;
import com.blogPlatform.III.blog.repository.ReviewRepository;
import com.blogPlatform.III.blog.repository.UserRepository;
import com.blogPlatform.III.blog.specification.PostSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PostService {

    public static final Sort FEED_SORT = Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id"));

    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final ReviewRepository reviewRepository;
    private final TagService tagService;
    private final ContentModerator contentModerator;

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.TAGS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true)})
    @Transactional(rollbackFor = ContentRejectedException.class)
    public PostResponse create(Long authorId, PostRequest request) throws ContentRejectedException {
        User author = userRepository.findOrThrow(authorId);

        checkTitleFree(author.getId(), request.title());

        Post post = new Post();
        post.setAuthor(author);
        applyContent(post, request.title(), request.content(), request.status(), request.tags());
        Post saved = postRepository.save(post);

        // after the INSERT on purpose: a rejection rolls back the post and its new tags (rollbackFor)
        contentModerator.check(saved.getTitle(), saved.getContent());

        return PostResponse.from(saved);
    }

    public PageResponse<PostResponse> search(PostFilter filter, Pageable pageable) {

        Specification<Post> spec = Specification.unrestricted();

        if (filter.status() != null) {
            spec = spec.and(PostSpecifications.hasStatus(filter.status()));
        }
        if (StringUtils.hasText(filter.author())) {
            spec = spec.and(PostSpecifications.hasAuthor(filter.author()));
        }
        if (StringUtils.hasText(filter.tag())) {
            spec = spec.and(PostSpecifications.hasTag(TagService.normalize(filter.tag())));
        }
        if (StringUtils.hasText(filter.keyword())) {
            spec = spec.and(PostSpecifications.matchesKeyword(filter.keyword()));
        }
        if (filter.from() != null) {
            spec = spec.and(PostSpecifications.createdFrom(filter.from()));
        }
        if (filter.to() != null) {
            spec = spec.and(PostSpecifications.createdTo(filter.to()));
        }

        Page<Post> page = postRepository.findAll(spec, pageable);
        return PageResponse.from(page.map(PostResponse::from));
    }

    public CursorPageResponse<PostResponse> feed(String cursor, int size) {
        ScrollPosition position = (cursor == null || cursor.isEmpty())
                ? ScrollPosition.keyset()
                : ScrollPosition.forward(FeedCursor.parse(cursor).keys());

        Window<Post> window = postRepository.findBy(Specification.unrestricted(), query -> query
                .sortBy(FEED_SORT)
                .limit(size)
                .scroll(position));

        List<Post> posts = window.getContent();
        List<PostResponse> content = posts.stream().map(PostResponse::from).toList();
        String nextCursor = window.hasNext() ? FeedCursor.of(posts.get(posts.size() - 1)).asText() : null;
        return new CursorPageResponse<>(content, size, window.hasNext(), nextCursor);
    }

    @Cacheable(cacheNames = CacheConfig.POSTS, key = "#id")
    public PostResponse getById(Long id) {
        return PostResponse.from(postRepository.findOrThrow(id));
    }

    public PageResponse<PostResponse> searchByKeyword(String keyword, Pageable pageable) {
        Page<Post> page = postRepository.searchFullText(keyword, pageable);
        return PageResponse.from(page.map(PostResponse::from));
    }

    @Cacheable(cacheNames = CacheConfig.TOP_RATED_POSTS,
            key = "#minRating + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public PageResponse<PostRatingDto> getTopRated(double minRating, Pageable pageable) {
        return PageResponse.from(postRepository.findTopRated(minRating, pageable));
    }

    public PageResponse<PostSummaryDto> getSummaries(Pageable pageable) {
        return PageResponse.from(postRepository.findSummaries(pageable));
    }

    @Cacheable(cacheNames = CacheConfig.POPULAR_POSTS, key = "#pageable.pageNumber + '-' + #pageable.pageSize")
    public PageResponse<PopularPostResponse> getPopular(Pageable pageable) {
        return PageResponse.from(postRepository.findPopular(pageable).map(PopularPostResponse::from));
    }

    @Caching(
            put = @CachePut(cacheNames = CacheConfig.POSTS, key = "#id"),
            evict = {
                    @CacheEvict(cacheNames = CacheConfig.TAGS, allEntries = true),
                    @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true),
                    @CacheEvict(cacheNames = CacheConfig.TOP_RATED_POSTS, allEntries = true)})
    @Transactional(rollbackFor = ContentRejectedException.class)
    public PostResponse update(Long id, PostUpdateRequest request) throws ContentRejectedException {
        Post post = postRepository.findOrThrow(id);

        if (!post.getVersion().equals(request.version())) {
            throw new ObjectOptimisticLockingFailureException(Post.class, id);
        }

        if (!post.getTitle().equals(request.title())) {
            checkTitleFree(post.getAuthor().getId(), request.title());
        }

        applyContent(post, request.title(), request.content(), request.status(), request.tags());
        Post saved = postRepository.saveAndFlush(post);

        contentModerator.check(saved.getTitle(), saved.getContent());
        return PostResponse.from(saved);
    }

    @Caching(
            put = @CachePut(cacheNames = CacheConfig.POSTS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true))
    @Transactional
    public PostResponse publish(Long id) {
        int updatedRows = postRepository.updateStatus(id, PostStatus.PUBLISHED, LocalDateTime.now());
        if (updatedRows == 0) {
            throw new ResourceNotFoundException("Post " + id + " not found");
        }
        return getById(id);
    }

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.POSTS, key = "#id"),
            @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.TOP_RATED_POSTS, allEntries = true)})
    @Transactional
    public void delete(Long id) {
        Post post = postRepository.findOrThrow(id);
        // a soft delete is an UPDATE, so ON DELETE CASCADE never fires: comments and reviews are soft-deleted here,
        // children first, while the post still counts as live in their UPDATE joins
        commentRepository.softDeleteByPost(id);
        reviewRepository.softDeleteByPost(id);
        postRepository.delete(post);
    }

    private void checkTitleFree(Long authorId, String title) {
        if (postRepository.existsByAuthorIdAndTitle(authorId, title)) {
            throw new DuplicateResourceException("This author already has a post titled '" + title + "'");
        }
    }

    private void applyContent(Post post, String title, String content, PostStatus status, List<String> tags) {
        post.setTitle(title);
        post.setContent(content);
        if (status != null) {
            post.setStatus(status);
        }
        if (tags != null) {
            post.setTags(tagService.findOrCreate(tags));
        }
    }
}
