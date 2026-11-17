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
    private final AuditService auditService;
    private final ContentModerator contentModerator;

    @Caching(evict = {
            @CacheEvict(cacheNames = CacheConfig.TAGS, allEntries = true),
            @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true)})
    @Transactional(rollbackFor = ContentRejectedException.class)
    public PostResponse create(Long authorId, PostRequest request) throws ContentRejectedException {
        auditService.record("CREATE_POST", null, "authorId=" + authorId + ", title=" + request.title());

        User author = userRepository.findById(authorId)
                .orElseThrow(() -> new ResourceNotFoundException("User " + authorId + " not found"));

        if (postRepository.existsByAuthorIdAndTitle(author.getId(), request.title())) {
            throw new DuplicateResourceException("This author already has a post titled '" + request.title() + "'");
        }

        Post post = new Post();
        post.setAuthor(author);
        post.setTitle(request.title());
        post.setContent(request.content());
        if (request.status() != null) {
            post.setStatus(request.status());
        }
        if (request.tags() != null) {
            post.setTags(tagService.findOrCreate(request.tags()));
        }
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
            spec = spec.and(PostSpecifications.hasTag(filter.tag().trim().toLowerCase()));
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
        return PostResponse.from(findPost(id));
    }

    public PageResponse<PostResponse> searchByKeyword(String keyword, Pageable pageable) {
        Page<Post> page = postRepository.searchFullText(keyword, pageable);
        return PageResponse.from(page.map(PostResponse::from));
    }

    // one cache entry per (minRating, page, size); the ranking has a fixed order, so no sort in the key
    @Cacheable(cacheNames = CacheConfig.TOP_RATED_POSTS,
            key = "#minRating + '-' + #pageable.pageNumber + '-' + #pageable.pageSize")
    public PageResponse<PostRatingDto> getTopRated(double minRating, Pageable pageable) {
        return PageResponse.from(postRepository.findTopRated(minRating, pageable));
    }

    public PageResponse<PostSummaryDto> getSummaries(Pageable pageable) {
        return PageResponse.from(postRepository.findSummaries(pageable));
    }

    // one cache entry per (page, size); the ranking has a fixed order, so no sort in the key
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
        auditService.record("UPDATE_POST", id, "title=" + request.title() + ", version=" + request.version());

        Post post = findPost(id);

        if (!post.getVersion().equals(request.version())) {
            throw new ObjectOptimisticLockingFailureException(Post.class, id);
        }

        if (!post.getTitle().equals(request.title())
                && postRepository.existsByAuthorIdAndTitle(post.getAuthor().getId(), request.title())) {
            throw new DuplicateResourceException("This author already has a post titled '" + request.title() + "'");
        }

        post.setTitle(request.title());
        post.setContent(request.content());
        if (request.status() != null) {
            post.setStatus(request.status());
        }
        if (request.tags() != null) {
            post.setTags(tagService.findOrCreate(request.tags()));
        }
        Post saved = postRepository.saveAndFlush(post);

        contentModerator.check(saved.getTitle(), saved.getContent());
        return PostResponse.from(saved);
    }

    @Caching(
            put = @CachePut(cacheNames = CacheConfig.POSTS, key = "#id"),
            evict = @CacheEvict(cacheNames = CacheConfig.POPULAR_POSTS, allEntries = true))
    @Transactional
    public PostResponse publish(Long id) {
        auditService.record("PUBLISH_POST", id, "status=PUBLISHED");

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
        auditService.record("DELETE_POST", id, "delete requested");

        Post post = findPost(id);
        // a soft delete is an UPDATE, so the database's ON DELETE CASCADE never fires:
        // the post's comments and reviews are soft-deleted here, in the same transaction.
        // Children first: Hibernate adds "deleted_at IS NULL" to the post join inside these UPDATEs.
        commentRepository.softDeleteByPost(id);
        reviewRepository.softDeleteByPost(id);
        // @SQLDelete on Post: UPDATE posts SET deleted_at = NOW(6) WHERE post_id = ? AND version = ?
        postRepository.delete(post);
    }

    private Post findPost(Long id) {
        return postRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Post " + id + " not found"));
    }
}
