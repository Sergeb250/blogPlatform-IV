# Blogging Platform API

A REST API for a blogging platform: users write posts, add tags, comment and review.

The focus is on **data access done well**: Spring Data JPA repositories, JPQL / native / Specification queries, pagination, transactions, query optimization and caching. Every optimization is measured on a 50,000-post dataset.

| | |
|---|---|
| Language / framework | Java 17+, Spring Boot 4.1.2 (Spring MVC, Spring Data JPA, Hibernate 7) |
| Database | MySQL 8 |
| Cache | Spring Cache + Caffeine (in memory) |
| API docs | springdoc OpenAPI 3 + Swagger UI |
| Other | Bean Validation, Spring AOP, Lombok, BCrypt (`spring-security-crypto`) |

Database design: [`Docs/db erd.png`](Docs/db%20erd.png)

---

## 1. Features

- **CRUD** for users, posts, tags, comments and reviews, with validation and one standard JSON response shape.
- **Queries:** derived queries, JPQL with DTO projections, native SQL, a `@Modifying` update, and **JPA Specifications** for combinable filters.
- **Pagination and sorting** with `Pageable` (default page size, max 100, sort-field whitelist), plus a **keyset (cursor) feed** that stays fast at any depth.
- **Full-text search** with a MySQL FULLTEXT index.
- **Transactions:** read-only by default, optimistic locking (`@Version`, 409 on conflict), `REQUIRES_NEW` audit log, `MANDATORY` tag helper, `REPEATABLE_READ` stats, `rollbackFor` checked exceptions.
- **Performance:** indexes, N+1 fixes, rewritten aggregate queries, an AOP timing aspect, and a before/after report.
- **Caching:** posts, users, tags, popular and top-rated posts. The cache is refreshed or cleared after every write that changes cached data. Statistics at `/api/cache-stats`.
- **Soft delete:** a delete keeps the row and sets `deleted_at`; the API never shows it again. Deleting a post or user also soft-deletes what belongs to it, and deleted names, titles and reviews can be used again.

---

## 2. Requirements

- **JDK 17 or newer.** The project is built and tested with JDK 27.
- **MySQL 8**, running locally. The user needs the right to create databases.
- No Maven install needed: the wrapper (`mvnw` / `mvnw.cmd`) is included.

---

## 3. Setup

### 3.1 Database
Nothing to create by hand. The JDBC URL contains `createDatabaseIfNotExist=true`, and Hibernate creates the tables (`ddl-auto=update`).

| Database | Used by |
|---|---|
| `blog_db` | the application |
| `blog_test` | the automated tests (they never touch `blog_db`) |

If your MySQL user is not allowed to create databases, create them first:
```sql
CREATE DATABASE blog_db;
CREATE DATABASE blog_test;
```

On the first start the app also creates:
- the B-tree indexes declared with `@Index` on the entities
- a FULLTEXT index on `posts(title, content)` (`util/FullTextIndexInitializer`)

### 3.2 Environment variables
The credentials are read from environment variables, so no password is stored in the code:

| Variable | Default | Example |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/blog_db?createDatabaseIfNotExist=true` | – |
| `DB_USERNAME` | `root` | `blog_user` |
| `DB_PASSWORD` | *(empty)* | `your-password` |
| `ADMIN_USERNAME` | `admin` | – |
| `ADMIN_EMAIL` | `admin@blog.local` | – |
| `ADMIN_PASSWORD` | *(empty: no admin is created)* | `Admin-pass1` |

Sign-up only ever creates READERs. The first **ADMIN** account is created at startup from the three `ADMIN_*`
variables, and only while no admin exists and `ADMIN_PASSWORD` has at least 8 characters. An existing account
with that username or email is never turned into an admin. After that, an admin gives roles with
`PUT /api/users/{id}/roles`.

```powershell
# Windows PowerShell (current window)
$env:DB_USERNAME = "root"
$env:DB_PASSWORD = "your-password"
```
```bash
# Linux / macOS / Git Bash
export DB_USERNAME=root
export DB_PASSWORD=your-password
```

### 3.3 Run
```bash
./mvnw spring-boot:run            # Windows: mvnw.cmd spring-boot:run
```
or build a jar and run it:
```bash
./mvnw clean package -DskipTests
java -jar target/blog-0.0.1-SNAPSHOT.jar
```
The API runs on **http://localhost:8080**.

### 3.4 Profiles

| Profile | Start with | What it does |
|---|---|---|
| *(none)* | `java -jar target/blog-0.0.1-SNAPSHOT.jar` | normal mode, SQL logging off |
| `dev` | `--spring.profiles.active=dev` | prints every SQL statement, Hibernate session metrics (SQL statements per request, to spot N+1 problems), and the time of every service/repository call |
| `seed` | `--spring.profiles.active=seed` | fills an **empty** `blog_db` with test data (see 3.5) |

### 3.5 Test data (optional)
```bash
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed
```
This fills an **empty** `blog_db` in about 30 seconds with:
- 1,000 users (`user1` … `user1000`, password `password123`)
- 50,000 posts and 30 tags
- 200,000 comments and ~100,000 reviews

It uses a fixed random seed, so the data is the same every time. It does nothing if the database already has data.

---

## 4. API documentation

| What | Where |
|---|---|
| Swagger UI (try every endpoint) | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Postman collection (by resource) | [`postman/blog-platform.postman_collection.json`](postman/blog-platform.postman_collection.json) |
| Postman collection (one folder per user story) | [`postman/blog-platform-user-stories.postman_collection.json`](postman/blog-platform-user-stories.postman_collection.json) |

**Postman:**
1. Import a file (*Import → File*).
2. Run the whole collection with the *Collection Runner*.
3. The requests run in order (47 in the first collection, 133 in the user-story one). Each one saves the ids it creates (`userId`, `postId`, …) for the next ones, and the last folder deletes everything again. Change the `baseUrl` collection variable if the app doesn't run on `localhost:8080`.

### Response format
Every endpoint answers with the same envelope:
```json
{ "success": true,  "status": 201, "message": "Post created successfully", "data": { "id": 1, "...": "..." }, "timestamp": "2026-11-09T15:30:00" }
{ "success": false, "status": 400, "message": "Validation failed", "errors": { "email": "must be a valid email address" }, "path": "/api/users", "timestamp": "..." }
```

| Status | When |
|---|---|
| 200 / 201 | OK / created (DELETE also answers 200 with a message) |
| 400 | any validation failure (see below), banned word |
| 401 | not logged in, or a bad token (see [Access rules](#access-rules-rbac)) |
| 403 | logged in, but your role or ownership doesn't allow it |
| 404 | id not found, unknown URL |
| 405 | wrong HTTP method |
| 409 | duplicate among live rows (username, email, tag, title, review), or **optimistic-lock conflict** |

### Endpoints

| Method | URL | Notes |
|---|---|---|
| POST / GET | `/api/users` | GET is paged |
| GET / PUT / DELETE | `/api/users/{id}` | GET is cached |
| PUT | `/api/users/{id}/roles` | ADMIN: `{"roles": ["AUTHOR", "READER"]}` |
| GET | `/api/users/by-username/{username}` | |
| POST / GET | `/api/posts` | GET: filters `status, author, tag, keyword, from, to` + `page, size, sort` |
| GET / PUT / DELETE | `/api/posts/{id}` | GET is cached; PUT needs the `version` you read |
| PATCH | `/api/posts/{id}/publish` | |
| GET | `/api/posts/feed?cursor=&size=` | keyset (cursor) paging |
| GET | `/api/posts/search?keyword=` | full-text, best match first |
| GET | `/api/posts/summaries` | light paged list |
| GET | `/api/posts/top-rated?minRating=&page=&size=` | paged ranking, cached 60 s per page |
| GET | `/api/posts/popular?page=&size=` | paged ranking, cached 60 s per page |
| POST / GET | `/api/posts/{postId}/comments` | GET is paged |
| GET | `/api/posts/{postId}/comments/count` | |
| PUT / DELETE | `/api/posts/{postId}/comments/{id}` | PUT needs the `version` you read |
| POST / GET | `/api/posts/{postId}/reviews` | one review per user per post; GET is paged |
| DELETE | `/api/posts/{postId}/reviews/{id}` | |
| POST / GET | `/api/tags` | GET is paged and cached |
| GET / DELETE | `/api/tags/{id}` | |
| GET | `/api/tags/usage` | posts per tag (native SQL), paged |
| GET | `/api/stats` | counts from one snapshot (REPEATABLE_READ) |
| GET | `/api/audit-logs` | every write attempt on posts |
| GET | `/api/cache-stats` | cache hits / misses |

Every `DELETE` is a **soft delete**, see [Deleting (soft delete)](#deleting-soft-delete).

### Access rules (RBAC)
Log in with `POST /api/auth/login` and send the token as `Authorization: Bearer <token>`.
The acting user always comes from the token: the bodies have no `authorId` / `userId` (sending one → 400).

| Endpoint | Anonymous | READER | AUTHOR | ADMIN |
|---|---|---|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | ✅ | ✅ | ✅ | ✅ |
| every `GET` on `/api/posts/**` and `/api/tags/**` (posts, comments, reviews, tags) | ✅ | ✅ | ✅ | ✅ |
| `GET /api/auth/me`, `GET /api/stats` | 401 | ✅ | ✅ | ✅ |
| `POST /api/posts` | 401 | 403 | ✅ | ✅ |
| `PUT`, `PATCH …/publish`, `DELETE` on `/api/posts/{id}` | 401 | 403 | own posts | ✅ |
| `POST` a comment or a review | 401 | ✅ | ✅ | ✅ |
| `PUT` / `DELETE` a comment, `DELETE` a review | 401 | own | own | ✅ |
| `POST /api/tags` | 401 | 403 | ✅ | ✅ |
| `DELETE /api/tags/{id}` | 401 | 403 | 403 | ✅ |
| `GET`, `PUT`, `DELETE` on `/api/users/{id}`, `GET /api/users/by-username/{username}` | 401 | own account | own account | ✅ |
| `GET /api/users`, `PUT /api/users/{id}/roles` | 401 | 403 | 403 | ✅ |
| `GET /api/audit-logs`, `GET /api/cache-stats`, `/api/admin/**` | 401 | 403 | 403 | ✅ |

- **401**: no token, or an expired / changed / malformed one. **403**: logged in, but not allowed.
- *own* = the caller wrote it (or is that user). A post or comment that doesn't exist is **404**, not 403.
- Changing a post needs the AUTHOR role **and** being its author: a former author can't change old posts.
- An admin can't remove ADMIN from their own account (409), so the platform always keeps an admin.
- New roles reach the API with the user's **next login**: a token keeps the roles it was signed with.

### Pagination and sorting
```
GET /api/posts?page=0&size=20&sort=createdAt,desc
GET /api/posts?status=PUBLISHED&tag=java&sort=title,asc
```
**Every GET that returns a list is paged.** Without parameters it answers page 0 with the default size and order below; there is no "give me everything" endpoint.

| List | Default size | Default order | Sort fields (anything else is a 400) |
|---|---:|---|---|
| `GET /api/posts` | 10 | newest first | `id, title, status, createdAt, updatedAt` |
| `GET /api/posts/summaries` | 20 | newest first | `id, title, createdAt` |
| `GET /api/posts/search` | 10 | best match | none (relevance) |
| `GET /api/posts/top-rated` | 10 | best average first | none (ranking) |
| `GET /api/posts/popular` | 10 | highest score first | none (ranking) |
| `GET /api/posts/{postId}/comments` | 10 | oldest first | `id, createdAt` |
| `GET /api/posts/{postId}/reviews` | 10 | newest first | `id, rating, createdAt` |
| `GET /api/users` | 20 | username A → Z | `id, username, createdAt` |
| `GET /api/tags` | 20 | name A → Z | `id, name` |
| `GET /api/tags/usage` | 20 | most used first | none (ranking) |
| `GET /api/audit-logs` | 20 | newest first | `id, action, createdAt` |
| `GET /api/posts/feed` | 10 | newest first | cursor instead of pages (see below) |

- **Response:** `data.content` plus `page`, `size`, `totalElements`, `totalPages`, `first`, `last`, `sort`.
- **Size:** allowed 1–100. A bigger size is a **400**, not silently cut down (`@ValidPageable`).
- **Not paged on purpose:** single items (`/{id}`, `/by-username/...`), the comment count, `/api/stats` (one object) and `/api/cache-stats` (always 5 caches, from memory).
- **Deep pages:** use the feed, `GET /api/posts/feed?size=20`, then send `cursor=<nextCursor>`. It costs the same at any depth. A deep OFFSET page costs 250–400 ms.

### Validation (strict)
Every input is checked with **Spring validation** (Bean Validation, `spring-boot-starter-validation`) before the service runs. A failure is a 400 that lists every bad field.

| Input | Rules |
|---|---|
| User body | `username` 3–50 chars, letters/digits/`.`/`_`/`-` · `email` valid, with a domain dot · `password` 8–100 chars with at least one letter and one digit |
| Post body | `authorId` > 0 · `title` 3–200 · `content` 1–10,000 · max 10 `tags`, each 1–50 chars (letters, digits, spaces, `. _ + # -`) · `version` ≥ 0 on update |
| Comment / review body | `userId` > 0 · comment `content` 1–2,000 · `rating` whole number 1–5 · `reviewText` ≤ 2,000 |
| Path ids | must be positive (`/api/posts/0` → 400) |
| Post filters | `author` = valid username · `tag` = valid tag name · `keyword` 3–100 · `from` ≤ `to` (yyyy-MM-dd) |
| Other parameters | feed `size` 1–100 and a valid `cursor` (real date + id, `@ValidCursor`) · search `keyword` 3–100 · top-rated `minRating` 1–5, `limit` 1–100 · popular `limit` 1–50 |
| JSON | unknown fields, `"5"` (text) for a number, `4.5` for a whole number, duplicate fields and trailing data are refused (`spring.jackson.*` in `application.properties`) |

```json
{ "success": false, "status": 400, "message": "Validation failed",
  "errors": { "pageable": "page size must be between 1 and 100", "dateRangeValid": "'from' must be on or before 'to'" } }
{ "success": false, "status": 400, "message": "Request body is not valid", "errors": { "isAdmin": "unknown field" } }
```
The rules live in the request records (`dto/request/`), on the controller parameters, and in `validation/`: four **custom constraint annotations, each with its own validator class** (`@ValidPageable` → `PageableValidator`, `@ValidCursor` → `CursorValidator`, `@Username` → `UsernameValidator`, `@TagName` → `TagNameValidator`). There is **no** hand-written validation code: the services only check business rules (already exists → 409, not found → 404, old version → 409, banned word → 400).

### Deleting (soft delete)
A delete does not remove the row. It sets `deleted_at` (users, posts, comments, reviews, tags), and from then on the API treats the row as gone: 404, not in any list, search, ranking or count. A second delete is a 404.

| Piece | Where | What it does |
|---|---|---|
| `@SQLDelete` | each entity | `repository.delete()` runs `UPDATE ... SET deleted_at = NOW(6) WHERE id = ? AND deleted_at IS NULL` instead of `DELETE` (plus `AND version = ?` for posts and comments). `verify = Expectation.RowCount.class` turns "0 rows changed" into a 409, so optimistic locking still works |
| `@SQLRestriction("deleted_at IS NULL")` | each entity | Hibernate adds the condition to every query it writes: `findById`, derived, JPQL, Specifications, joins, collections |
| native queries | `searchFullText`, `findPopular`, `findTagUsage` | `deleted_at IS NULL` written by hand (native SQL is not touched by Hibernate) |
| cascade | `PostService.delete`, `UserService.delete` | an UPDATE does not fire `ON DELETE CASCADE`, so the service soft-deletes the dependent rows in the same transaction: a post → its comments and reviews; a user → their posts, plus the comments and reviews they wrote or received |
| unique keys on live rows | `config/SoftDeleteSchemaInitializer` | MySQL has no partial unique index, so a generated column `not_deleted` (1 = live, NULL = deleted) ends every unique key, e.g. `UNIQUE (username, not_deleted)`. Deleted usernames, emails, titles, tag names and reviews can be used again; two **live** duplicates are still refused |

The schema change runs once at startup, after Hibernate's `ddl-auto=update` (existing rows stay live). Deleted rows can be seen or restored in MySQL: `SELECT * FROM posts WHERE deleted_at IS NOT NULL;` / `UPDATE posts SET deleted_at = NULL WHERE post_id = ?;`.

---

## 5. Caching configuration

The caches are set up in [`config/CacheConfig.java`](src/main/java/com/blogPlatform/III/blog/config/CacheConfig.java): Spring Cache with **Caffeine**, `@EnableCaching(order = HIGHEST_PRECEDENCE)`.

| Cache | Holds (always DTOs) | Key | Max entries | TTL |
|---|---|---|---:|---|
| `posts` | `PostResponse` | post id | 10,000 | 10 min |
| `users` | `UserResponse` | user id | 5,000 | 30 min |
| `tags` | one page of tags | `page-size-sort`, e.g. `0-20-name: ASC` | 100 | 1 h |
| `popularPosts` | one page of popular posts | `page-size` | 20 | 60 s |
| `topRatedPosts` | one page of top-rated posts | `minRating-page-size` | 50 | 60 s |

**Never stale after a write.** Every write that changes cached data updates or clears it:

| Write | Cache effect |
|---|---|
| update / publish a post | `@CachePut` the new post |
| delete a post | evict the post, popular and top-rated |
| add / delete a comment | evict popular |
| add / delete a review | evict popular and top-rated |
| create a post | evict tags (new tags) and popular |
| update a user | `@CachePut` the user + evict posts (they show the author name) |
| create / delete a tag | evict tags (delete also evicts posts) |

**The cache runs outside the transaction.** A hit needs no DB connection, and cache updates happen only **after** the commit.

**Monitoring:** `GET /api/cache-stats` shows size, hits, misses, hit rate and evictions per cache (`recordStats()`).

**Changing a setting:** edit the `registerCustomCache(...)` line in `CacheConfig`. For example, `build(20, Duration.ofSeconds(60))` = max 20 entries, 60 s TTL.

**Effect** (measured, see the report):
- popular posts: 973 ms → **3 ms**
- top rated: 1098 ms → **3 ms**
- a cache hit runs **0 SQL statements**

---

## 6. Testing

```bash
./mvnw test
```
This runs **90 tests**: integration tests against the real MySQL database `blog_test`, plus fast unit tests for the custom validators and the password util. MySQL must be running and `DB_PASSWORD` must be set.

| Test class | What it checks |
|---|---|
| `ApiTest` | every endpoint over HTTP (MockMvc): status codes, JSON shape, paging (every list endpoint answers page 0 with its default size and order), 409 on old versions, cache stats, and **strict validation** (bad bodies, unknown/wrong-typed JSON fields, ids, query parameters, page size, sort whitelist, date range) |
| `RepositoryTest` | derived, JPQL, native and `@Modifying` queries, Specifications, pagination |
| `TransactionTest` | rollback (all or nothing), `rollbackFor`, `REQUIRES_NEW`, `MANDATORY`, optimistic locking, `REPEATABLE_READ` vs `READ_COMMITTED` |
| `QueryOptimizationTest` | full-text search, keyset feed, **N+1 guard** (a page of posts must need ≤ 4 SQL statements) |
| `CacheTest` | cache hits run 0 SQL and 0 transactions, every write keeps the cache correct, per-cache size and TTL |
| `SoftDeleteTest` | deleted rows stay in the tables (checked with plain SQL) but no query returns them, the cascade of post and user deletes, a delete with an old version is refused, reuse of deleted names / titles / reviews, live duplicates still refused by the database |
| `CustomValidatorsTest` | unit test (no Spring): each custom validator, `@Username`, `@TagName` (also inside a list), `@ValidCursor` |
| `PasswordUtilTest` | unit test: the BCrypt hash is never the password, it matches, and it is different every time (salt) |

- **Rollback vs commit:** most tests run in a transaction that is rolled back. `TransactionTest`, `QueryOptimizationTest`, `CacheTest` and `SoftDeleteTest` really commit (needed for rollback and FULLTEXT behaviour) and delete their data afterwards.
- **Caches:** every test class clears the caches before each test.

### Performance tests (on the seeded `blog_db`)
They are skipped in a normal `mvnw test`. Run them on purpose:
```bash
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed     # once
./mvnw test -Dtest=PerformanceTest -Dperf=true         # 16 endpoint scenarios + cache miss/hit
./mvnw test -Dtest=PagingStrategiesTest -Dperf=true    # in-memory vs OFFSET vs keyset paging
```
The results are written to `target/*.md`.

### Manual testing
- Swagger UI: http://localhost:8080/swagger-ui.html
- Postman: the collection in `postman/`

---

## 7. Project structure

```
src/main/java/com/blogPlatform/III/blog
├── aop/             PerformanceAspect: times every service / repository call
├── config/          CacheConfig, OpenApiConfig, PasswordConfig, SoftDeleteSchemaInitializer
├── controller/      REST endpoints (thin: validate, call the service, wrap in ApiResponse)
├── dto/request/     what clients send (records + Bean Validation)
├── dto/response/    what the API returns (records, ApiResponse, PageResponse, ...)
├── entity/          JPA entities: User, Post, Comment, Review, Tag, AuditLog
├── exception/       custom exceptions + GlobalExceptionHandler
├── repository/      Spring Data JPA repositories (all queries live here)
├── seed/            DataSeeder (profile "seed")
├── service/         business rules, @Transactional, @Cacheable
├── specification/   JPA Specifications for the post filters
├── util/            FullTextIndexInitializer (creates the FULLTEXT index), MySqlFunctionContributor (match_against)
└── validation/      custom constraints, each = annotation + validator class:
                     @ValidPageable (page size + sort whitelist), @ValidCursor, @Username, @TagName
```

## 8. More documentation

| Document | Content |
|---|---|
| [`Docs/repositories-and-queries.md`](Docs/repositories-and-queries.md) | every repository and query: derived, JPQL, native, Specifications, projections, keyset |
| [`Docs/transactions.md`](Docs/transactions.md) | transaction strategy: propagation, isolation, rollback rules, optimistic locking, cache ordering |
| [`Docs/performance-report.md`](Docs/performance-report.md) | measured before/after: indexes, N+1, search, paging, caching, `EXPLAIN` plans |
