# Blogging Platform API – Phase IV (Security)

A REST API for a blogging platform: users write posts, add tags, comment and review.

Phase IV makes it **secure and production-ready**: Spring Security with **JWT** login, **Google OAuth2** login,
**role-based access** (`ADMIN`, `AUTHOR`, `READER`), **CORS**, a reasoned **CSRF** setup, **BCrypt** passwords, a
**token blacklist** and session registry (hash maps), **brute-force protection** and a **security event log**.
The data layer of Phase III (JPA queries, paging, transactions, caching, soft delete) is unchanged underneath.

| | |
|---|---|
| Language / framework | Java 21, Spring Boot 4.1.2 (Spring MVC, Spring Data JPA, Hibernate 7) |
| Security | Spring Security 7, JJWT 0.12.6 (HS256), Spring Security OAuth2 Client (Google), BCrypt |
| Database | MySQL 8 |
| Cache | Spring Cache + Caffeine (in memory) |
| API docs | springdoc OpenAPI 3 + Swagger UI (with a JWT "Authorize" button) |
| Other | Bean Validation, Spring AOP, Lombok, Logback |

Database design: [`Docs/erd.png`](Docs/erd.png) · How it was built, phase by phase: [`phases.md`](phases.md)

---

## 1. Features

**Security (Phase IV)**
- **JWT login:** `POST /api/auth/login` returns a token signed with HMAC-SHA256 (claims `sub`, `uid`, `roles`, `iat`,
  `exp`, `jti`, `iss`). A filter checks it on every request; expired, changed, unsigned or malformed tokens get **401**.
- **Google login (OAuth2):** the first Google login creates a `READER` account (no password), later logins find it by
  the verified email; the answer is our own JWT.
- **Roles and ownership:** `@PreAuthorize` on every protected endpoint; authors change only their own posts, users
  only their own comments, reviews and account; admins everything. The acting user always comes from the token.
- **Stateless:** no server session, CSRF off for the API (with the reason), **CORS** allow-list for browser frontends.
- **Passwords:** BCrypt (salted, slow on purpose). Sign-up always creates a `READER`; only an admin gives roles.
- **Revocation:** logout puts the token on a **blacklist** (hash map, O(1) per request); a role, password or account
  change revokes **all** tokens of that user; admins see active sessions and can log a user out everywhere.
- **Brute force:** 5 wrong passwords for a username from one IP within 15 minutes → **429** with `Retry-After`.
- **Monitoring:** every security event is one `key=value` line in `logs/security.log`; an admin report shows
  logins, rejected tokens, 401/403 counts, suspected brute force and the busiest endpoints and users.

**Data access (Phase III)**
- CRUD for users, posts, tags, comments and reviews, with strict validation and one standard JSON response shape.
- Derived, JPQL, native and Specification queries; paging and sorting with a whitelist; a keyset (cursor) feed.
- MySQL full-text search; transactions (optimistic locking, `REQUIRES_NEW` audit log, `REPEATABLE_READ` stats).
- Caffeine caching that is never stale after a write; soft delete everywhere.

---

## 2. Requirements

- **JDK 21 or newer.** The project is built and tested with JDK 27 (compiled for release 21).
- **MySQL 8**, running locally. The user needs the right to create databases.
- No Maven install needed: the wrapper (`mvnw` / `mvnw.cmd`) is included.
- Optional: a Google Cloud OAuth client, for Google login (see 4.2).

---

## 3. Setup

### 3.1 Database
Nothing to create by hand. The JDBC URL contains `createDatabaseIfNotExist=true`, and Hibernate creates the tables
(`ddl-auto=update`).

| Database | Used by |
|---|---|
| `blog_db` | the application |
| `blog_test` | the automated tests (they never touch `blog_db`) |

On startup the app also creates (once, then it checks and does nothing):
- the three roles `ADMIN`, `AUTHOR`, `READER`, and it moves a Phase III `users.role` column into `user_roles`
  (`util/RoleSchemaInitializer`)
- unique keys that ignore soft-deleted rows (`util/SoftDeleteSchemaInitializer`)
- a FULLTEXT index on `posts(title, content)` (`util/FullTextIndexInitializer`)

> A Phase III database is migrated in place (the `role` column is copied, then dropped). Use a copy of `blog_db`
> if you still want to run the Phase III code (`main` branch) on it.

### 3.2 Environment variables
Secrets are read from environment variables; nothing secret is stored in the code.

| Variable | Default | What |
|---|---|---|
| `DB_URL` | `jdbc:mysql://localhost:3306/blog_db?createDatabaseIfNotExist=true` | database |
| `DB_USERNAME` | `root` | database user |
| `DB_PASSWORD` | *(empty)* | database password |
| `JWT_SECRET` | a development key | **base64, at least 32 bytes**; create one with `openssl rand -base64 32`. Set it everywhere except your own machine |
| `JWT_EXPIRATION` | `60m` | token lifetime (`30s`, `15m`, `2h`, …) |
| `ADMIN_USERNAME` / `ADMIN_EMAIL` | `admin` / `admin@blog.local` | the first admin account |
| `ADMIN_PASSWORD` | *(empty: no admin is created)* | 8+ characters, e.g. `Admin-pass1` |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:3000` | browser frontends allowed to call the API, comma-separated |
| `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET` | – | the Google OAuth client (profile `google`) |
| `OAUTH2_REDIRECT_URI` | *(empty: JSON answer)* | a frontend page that receives `#access_token=...` after a Google login |

Sign-up only ever creates READERs. The first **ADMIN** account is created at startup from the three `ADMIN_*`
variables, and only while no admin exists and `ADMIN_PASSWORD` has at least 8 characters. An existing account
with that username or email is never turned into an admin. After that, an admin gives roles with
`PUT /api/users/{id}/roles`.

```powershell
# Windows PowerShell (current window)
$env:DB_PASSWORD = "your-password"
$env:ADMIN_PASSWORD = "Admin-pass1"
```
```bash
# Linux / macOS / Git Bash
export DB_PASSWORD=your-password
export ADMIN_PASSWORD=Admin-pass1
```

### 3.3 Run
```bash
./mvnw spring-boot:run                                    # Windows: mvnw.cmd spring-boot:run
./mvnw spring-boot:run "-Dspring-boot.run.profiles=google"  # with Google login
```
or build a jar and run it:
```bash
./mvnw clean package -DskipTests
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.profiles.active=google
```
The API runs on **http://localhost:8080**.

### 3.4 Profiles

| Profile | Start with | What it does |
|---|---|---|
| *(none)* | `java -jar target/blog-0.0.1-SNAPSHOT.jar` | normal mode, Google login off |
| `google` | `--spring.profiles.active=google` | Google login on (needs `GOOGLE_CLIENT_ID` / `GOOGLE_CLIENT_SECRET`) |
| `dev` | `--spring.profiles.active=dev` | prints every SQL statement, Hibernate session metrics and the time of every service/repository call |
| `seed` | `--spring.profiles.active=seed` | fills an **empty** `blog_db` with test data (see 3.5) |

Profiles can be combined: `--spring.profiles.active=google,dev`.

### 3.5 Test data (optional)
```bash
java -jar target/blog-0.0.1-SNAPSHOT.jar --spring.profiles.active=seed
```
This fills an **empty** `blog_db` in about 30 seconds with 1,000 users (`user1` … `user1000`, password
`password123`; users 1–200 are AUTHORs, the rest READERs), 50,000 posts, 30 tags, 200,000 comments and ~100,000
reviews. It uses a fixed random seed and does nothing if the database already has data.

---

## 4. Security

### 4.1 Log in with a JWT
```http
POST /api/auth/register      { "username": "anna", "email": "anna@test.com", "password": "secret123" }   → 201, READER
POST /api/auth/login         { "username": "anna", "password": "secret123" }                              → 200
GET  /api/auth/me            Authorization: Bearer <accessToken>                                          → 200
POST /api/auth/logout        Authorization: Bearer <accessToken>                                          → 200, token revoked
```
```json
{ "success": true, "status": 200, "message": "Logged in successfully",
  "data": { "accessToken": "eyJhbGciOiJIUzI1NiJ9...", "tokenType": "Bearer", "expiresIn": 3600,
            "expiresAt": "2026-11-19T13:10:00Z", "username": "anna", "roles": ["READER"] } }
```
The token is three base64url parts, `header.payload.signature`. The payload can be read by anyone (jwt.io, or the
Postman collection's test script):
```json
{ "jti": "5f3b9c2e-...", "iss": "blog-platform", "sub": "anna", "uid": 7, "roles": ["READER"],
  "iat": 1763379000, "exp": 1763382600 }
```
The signature is `HMAC-SHA256(JWT_SECRET, header.payload)`: changing one letter of the payload (`READER` → `ADMIN`)
makes it invalid.

| 401 message | When |
|---|---|
| `Authentication required: log in and send 'Authorization: Bearer <token>'` | no token on a protected endpoint |
| `Token has expired, please log in again` | `exp` is in the past |
| `Token signature is not valid` | header or payload changed, or signed with another secret |
| `Unsigned tokens are not accepted` | `"alg": "none"` |
| `Token is malformed or not valid` | broken text, wrong issuer |
| `Token has been revoked, please log in again` | after logout, a role / password change, an account delete or an admin's forced logout |
| `Invalid username or password` | wrong login (the same text for an unknown user) |

A bad token is refused even on a public endpoint; another `Authorization` scheme (for example `Basic`) is ignored.

### 4.2 Google login (OAuth2)
1. Google Cloud console → *APIs & Services → Credentials → Create credentials → OAuth client ID* (Web application).
   Authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`.
2. Give the app the client id and secret, either as environment variables (`GOOGLE_CLIENT_ID`,
   `GOOGLE_CLIENT_SECRET`) or in a local file **`config/application-google.properties`** at the project root (the
   folder is git-ignored; Spring Boot reads it automatically):
   ```properties
   spring.security.oauth2.client.registration.google.client-id=1234-abc.apps.googleusercontent.com
   spring.security.oauth2.client.registration.google.client-secret=GOCSPX-...
   ```
3. Run with the `google` profile and open **http://localhost:8080/oauth2/authorization/google** in a browser.

After Google, the app finds the user by the **verified** email (or creates a READER with provider `GOOGLE`, Google's
id and no password) and answers with its own JWT, as JSON, or as a redirect to `OAUTH2_REDIRECT_URI` with
`#access_token=...`. An unverified Google email is refused. Without the `google` profile the app runs normally and
Google login is simply off.

### 4.3 Access rules (RBAC)
Log in with `POST /api/auth/login` and send the token as `Authorization: Bearer <token>`.
The acting user always comes from the token: the bodies have no `authorId` / `userId` (sending one → 400).

| Endpoint | Anonymous | READER | AUTHOR | ADMIN |
|---|---|---|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | ✅ | ✅ | ✅ | ✅ |
| every `GET` on `/api/posts/**` and `/api/tags/**` (posts, comments, reviews, tags) | ✅ | ✅ | ✅ | ✅ |
| `GET /api/auth/me`, `POST /api/auth/logout`, `GET /api/stats` | 401 | ✅ | ✅ | ✅ |
| `POST /api/posts` | 401 | 403 | ✅ | ✅ |
| `PUT`, `PATCH …/publish`, `DELETE` on `/api/posts/{id}` | 401 | 403 | own posts | ✅ |
| `POST` a comment or a review | 401 | ✅ | ✅ | ✅ |
| `PUT` / `DELETE` a comment, `DELETE` a review | 401 | own | own | ✅ |
| `POST /api/tags` | 401 | 403 | ✅ | ✅ |
| `DELETE /api/tags/{id}` | 401 | 403 | 403 | ✅ |
| `GET`, `PUT`, `DELETE` on `/api/users/{id}`, `GET /api/users/by-username/{username}` | 401 | own account | own account | ✅ |
| `GET /api/users`, `PUT /api/users/{id}/roles` | 401 | 403 | 403 | ✅ |
| `GET /api/audit-logs`, `GET /api/cache-stats`, `/api/admin/**` | 401 | 403 | 403 | ✅ |

- **401**: no token, or an expired / changed / malformed / revoked one. **403**: logged in, but not allowed.
- *own* = the caller wrote it (or is that user). A post or comment that doesn't exist is **404**, not 403.
- Changing a post needs the AUTHOR role **and** being its author: a former author can't change old posts.
- An admin can't remove ADMIN from their own account (409), so the platform always keeps an admin.
- A role change revokes the user's tokens: the next login carries the new roles.
- Every endpoint's exact rule (its `@PreAuthorize`) is also shown in Swagger UI, at the end of its description.

### 4.4 Logout and revoked tokens
A JWT can't be deleted (the server keeps no copy), so the app keeps two in-memory hash maps:

| Map | Key → value | Used for |
|---|---|---|
| `TokenBlacklist` | token id (`jti`) → expiry | one O(1) lookup on every request; expired entries are purged every minute |
| `ActiveSessionRegistry` | token id → session (user, IP, requests, last seen), user id → token ids | "log this user out everywhere" without scanning all sessions |

| Event | Effect |
|---|---|
| `POST /api/auth/logout` | that token only; the user's other devices stay logged in |
| `PUT /api/users/{id}/roles`, `PUT /api/users/{id}` (new password), `DELETE /api/users/{id}` | every token of that user |
| `DELETE /api/admin/security/users/{id}/sessions` (ADMIN) | every token of that user |
| `GET /api/admin/security/sessions` (ADMIN) | lists every token in use |

Both maps live in memory: after a restart they are empty (tokens are short-lived). Several servers would share
them through Redis.

### 4.5 CORS vs CSRF
Two different browser problems, often confused:

| | **CORS** (Cross-Origin Resource Sharing) | **CSRF** (Cross-Site Request Forgery) |
|---|---|---|
| What it is | a browser rule: may a page from origin A **read** the answer of origin B? | an attack: a foreign page makes your browser **send** a request with your cookie |
| Who sets it | the server sends `Access-Control-Allow-*` headers, the browser enforces them | the attacker; the server must defend itself |
| Protects | the answer (JavaScript of an unknown page can't read it) | the action (a forged request must not change anything) |
| Needs | an allow-list of origins | a secret token in every form + `SameSite` cookies |
| Applies to | browsers only (Postman and curl ignore it) | logins kept in **cookies** |

**In this project**
- **CORS on the API:** only the origins in `CORS_ALLOWED_ORIGINS` (default `http://localhost:3000`) may call it from a
  browser; methods `GET POST PUT PATCH DELETE`, headers `Authorization` and `Content-Type`; `Retry-After` is readable
  by the frontend. A preflight (or request) from any other origin, or for another method / header, gets **403**.
  The preflight needs no token.
- **No CSRF token on the API:** the JWT travels in the `Authorization` header, which only our own JavaScript can set.
  A foreign page can make the browser send cookies, but not this header, and the API has no login cookie. So CSRF has
  nothing to abuse, and the API stays stateless (`csrf().disable()`, no session).
- **When CSRF protection is needed:** an app that keeps the login in a **cookie** (server session, `JSESSIONID`) must
  turn Spring Security's CSRF filter on: every form carries a hidden `_csrf` token that a foreign page can't know,
  and a POST without it gets 403. This API has no such cookie, so it doesn't need it. The only cookie it ever sets
  (during the Google login round trip) is `HttpOnly` and `SameSite=Lax`.
- **CORS does not stop CSRF:** a plain HTML form post is sent by the browser no matter what CORS says (CORS only hides
  the answer). That's why cookie-based apps need the CSRF token as well.

### 4.6 Brute-force protection and the security log
**Sliding window:** for every `username|ip` the app keeps the times of the recent failed logins (a queue, oldest
first). 5 failures inside 15 minutes block that pair until the oldest one is 15 minutes old:
```
HTTP/1.1 429 Too Many Requests
Retry-After: 899
{ "success": false, "status": 429, "message": "Too many failed logins. Try again in 15 minute(s)", ... }
```
While blocked, even the right password gets 429. The owner on another network is not affected. A correct password
resets the count. Change the limits with `app.security.login.max-failures` / `app.security.login.window`.

**Security log** (`logs/security.log`, a new file every day, 30 days kept, git-ignored): one `key=value` line per event.
```
2026-11-18T11:44:34.298+02:00 WARN  LOGIN_FAILURE user=victim ip=203.0.113.7 failures_for_user=5
2026-11-18T11:44:34.360+02:00 WARN  LOGIN_BLOCKED user=victim ip=203.0.113.7 retry_after_s=899
2026-11-18T11:45:02.120+02:00 INFO  LOGIN_SUCCESS user=anna ip=10.0.0.4 method=PASSWORD
2026-11-18T11:45:10.004+02:00 WARN  TOKEN_REJECTED ip=10.0.0.9 method=GET path=/api/auth/me reason="Token signature is not valid"
2026-11-18T11:46:00.551+02:00 WARN  ACCESS_DENIED user=anna ip=10.0.0.4 method=GET path=/api/admin/security/report
2026-11-18T11:47:12.900+02:00 INFO  TOKENS_REVOKED user_id=7 count=2 reason=ROLE_CHANGE
```
Events: `LOGIN_SUCCESS` (`method=PASSWORD|GOOGLE`), `LOGIN_FAILURE`, `LOGIN_BLOCKED`, `GOOGLE_LOGIN_FAILURE`,
`TOKEN_REJECTED`, `ACCESS_UNAUTHENTICATED` (401), `ACCESS_DENIED` (403), `LOGOUT`, `TOKENS_REVOKED`. Line breaks in
user input are replaced, so nobody can forge a line (log injection).
```bash
grep LOGIN_FAILURE logs/security.log | grep -o "ip=[^ ]*" | sort | uniq -c | sort -rn     # failing IPs
```
```powershell
Select-String -Path logs\security.log -Pattern "LOGIN_BLOCKED|ACCESS_DENIED"
```

**Report** (`GET /api/admin/security/report`, ADMIN): login successes / failures / blocks, rejected tokens, 401 and
403 counts, active and revoked tokens, who is blocked now, **suspected brute force** (every username and IP with 5+
failed logins) and the top 5 failing usernames, failing IPs, **endpoints** (`/api/posts/7` counts as
`/api/posts/{id}`) and **users**. Counted since the last start.

### 4.7 Hashing and data structures (DSA)

| Where | Technique | Why |
|---|---|---|
| passwords | **BCrypt** (random salt, cost 10) | one-way and slow on purpose: a stolen database is expensive to crack |
| token signature | **HMAC-SHA256** with `JWT_SECRET` | fast keyed hash: proves the token is ours and unchanged, on every request |
| revoked tokens | `ConcurrentHashMap<jti, expiry>` | O(1) check per request, thread-safe, purged every minute |
| active sessions | two hash maps (by token id, by user id) | logout = O(1), "all tokens of a user" = one lookup |
| failed logins | `ConcurrentHashMap<username|ip, Deque<time>>` | sliding window: old entries leave at the front, O(removed) |
| top 5 in the report | min-heap (`PriorityQueue`) of size k | O(n log k) instead of sorting everything |

---

## 5. API documentation

| What | Where |
|---|---|
| Swagger UI (try every endpoint) | http://localhost:8080/swagger-ui.html |
| OpenAPI JSON | http://localhost:8080/v3/api-docs |
| Postman collection (every security rule, with tests) | [`postman/blog-platform-IV-security.postman_collection.json`](postman/blog-platform-IV-security.postman_collection.json) |

**Swagger UI:** log in with `POST /api/auth/login`, copy `accessToken`, click **Authorize** and paste it. Endpoints
without a lock are public; every description ends with its **Access** rule (the endpoint's `@PreAuthorize`).

**Postman:**
1. Start the app with `ADMIN_PASSWORD` set (the first start creates the admin).
2. *Import → File* the collection; set the collection variables `adminUsername` / `adminPassword` to the same values.
3. Run the whole collection with the *Collection Runner*, in order: 38 requests in 7 folders (sign-up and JWT, bad
   tokens, roles and ownership, CORS, logout and revocation, brute force and the report, clean-up). Every run uses
   fresh usernames and deletes what it created.

### Response format
Every endpoint answers with the same envelope:
```json
{ "success": true,  "status": 201, "message": "Post created successfully", "data": { "id": 1, "...": "..." }, "timestamp": "2026-11-09T15:30:00" }
{ "success": false, "status": 400, "message": "Validation failed", "errors": { "email": "must be a valid email address" }, "path": "/api/auth/register", "timestamp": "..." }
```

| Status | When |
|---|---|
| 200 / 201 | OK / created (DELETE also answers 200 with a message) |
| 400 | any validation failure (see below), banned word |
| 401 | not logged in, or a bad / revoked token, or a wrong login (see 4.1) |
| 403 | logged in, but your role or ownership doesn't allow it; a browser origin that is not allowed (CORS) |
| 404 | id not found, unknown URL |
| 405 | wrong HTTP method |
| 409 | duplicate among live rows (username, email, tag, title, review), **optimistic-lock conflict**, removing your own ADMIN role |
| 429 | too many failed logins (`Retry-After` header) |

### Endpoints

| Method | URL | Notes |
|---|---|---|
| POST | `/api/auth/register` | sign-up, always READER |
| POST | `/api/auth/login` | returns the JWT |
| GET / POST | `/api/auth/me` / `/api/auth/logout` | current user / revoke this token |
| GET | `/oauth2/authorization/google` | Google login (browser, profile `google`) |
| GET | `/api/users` | ADMIN, paged |
| GET / PUT / DELETE | `/api/users/{id}` | own account or ADMIN; GET is cached |
| PUT | `/api/users/{id}/roles` | ADMIN: `{"roles": ["AUTHOR", "READER"]}` |
| GET | `/api/users/by-username/{username}` | own account or ADMIN |
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
| GET | `/api/audit-logs` | ADMIN: every write attempt on posts |
| GET | `/api/cache-stats` | ADMIN: cache hits / misses |
| GET | `/api/admin/security/report` | ADMIN: security report (4.6) |
| GET | `/api/admin/security/sessions` | ADMIN: active tokens |
| DELETE | `/api/admin/security/users/{id}/sessions` | ADMIN: log a user out everywhere |

Every `DELETE` of a resource is a **soft delete**, see [Deleting (soft delete)](#deleting-soft-delete).

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
- **Not paged on purpose:** single items, the comment count, `/api/stats`, `/api/cache-stats`, the security report and the session list.
- **Deep pages:** use the feed, `GET /api/posts/feed?size=20`, then send `cursor=<nextCursor>`. It costs the same at any depth.

### Validation (strict)
Every input is checked with Bean Validation before the service runs. A failure is a 400 that lists every bad field.

| Input | Rules |
|---|---|
| Sign-up / user body | `username` 3–50 chars, letters/digits/`.`/`_`/`-` · `email` valid, with a domain dot · `password` 8–100 chars with at least one letter and one digit · no `role` field |
| Login body | `username` and `password` not blank (no format rules: they would help a guesser) |
| Roles body | `roles` not empty, each one of `ADMIN`, `AUTHOR`, `READER` |
| Post body | `title` 3–200 · `content` 1–10,000 · max 10 `tags`, each 1–50 chars (letters, digits, spaces, `. _ + # -`) · `version` ≥ 0 on update · **no `authorId`** (the author is the token's user) |
| Comment / review body | comment `content` 1–2,000 · `rating` whole number 1–5 · `reviewText` ≤ 2,000 · **no `userId`** |
| Path ids | must be positive (`/api/posts/0` → 400) |
| Post filters | `author` = valid username · `tag` = valid tag name · `keyword` 3–100 · `from` ≤ `to` (yyyy-MM-dd) |
| JSON | unknown fields, `"5"` (text) for a number, `4.5` for a whole number, duplicate fields and trailing data are refused |

```json
{ "success": false, "status": 400, "message": "Request body is not valid", "errors": { "authorId": "unknown field" } }
```
The rules live in the request records (`dto/request/`), on the controller parameters, and in `validation/`
(`@ValidPageable`, `@ValidCursor`, `@Username`, `@TagName`, each with its own validator class).

### Deleting (soft delete)
A delete does not remove the row. It sets `deleted_at` (users, posts, comments, reviews, tags), and from then on the API treats the row as gone: 404, not in any list, search, ranking or count. A second delete is a 404. A deleted user can't log in and their tokens are revoked.

| Piece | Where | What it does |
|---|---|---|
| `@SQLDelete` | each entity | `repository.delete()` runs `UPDATE ... SET deleted_at = NOW(6) WHERE id = ? AND deleted_at IS NULL` instead of `DELETE` (plus `AND version = ?` for posts and comments) |
| `@SQLRestriction("deleted_at IS NULL")` | each entity | Hibernate adds the condition to every query it writes |
| native queries | `searchFullText`, `findPopular`, `findTagUsage` | `deleted_at IS NULL` written by hand |
| cascade | `PostService.delete` | an UPDATE does not fire `ON DELETE CASCADE`, so the service soft-deletes a post's comments and reviews in the same transaction |
| unique keys on live rows | `util/SoftDeleteSchemaInitializer` | a generated column `not_deleted` (1 = live, NULL = deleted) ends every unique key, so deleted usernames, emails, titles, tag names and reviews can be used again |

---

## 6. Caching configuration

The caches are set up in [`config/CacheConfig.java`](src/main/java/com/blogPlatform/III/blog/config/CacheConfig.java): Spring Cache with **Caffeine**, `@EnableCaching(order = HIGHEST_PRECEDENCE)`.

| Cache | Holds (always DTOs) | Key | Max entries | TTL |
|---|---|---|---:|---|
| `posts` | `PostResponse` | post id | 10,000 | 10 min |
| `users` | `UserResponse` | user id | 5,000 | 30 min |
| `tags` | one page of tags | `page-size-sort` | 100 | 1 h |
| `popularPosts` | one page of popular posts | `page-size` | 20 | 60 s |
| `topRatedPosts` | one page of top-rated posts | `minRating-page-size` | 50 | 60 s |

Every write that changes cached data updates or clears it (`@CachePut` / `@CacheEvict`), the cache runs outside the
transaction (a hit needs no DB connection), and `GET /api/cache-stats` (ADMIN) shows hits, misses and evictions.

---

## 7. Testing

```bash
./mvnw test
```
This runs **200 tests** (3 performance tests are skipped unless asked for): integration tests against the real MySQL
database `blog_test` with real signed JWTs, plus fast unit tests. MySQL must be running and `DB_PASSWORD` set.

| Test class | What it checks |
|---|---|
| `SecurityConfigTest` | public vs protected URLs, JSON 401 / 403, stateless (no session), the login check (BCrypt, same error for unknown user and wrong password), app runs without Google keys |
| `JwtAuthTest` | sign-up, login, token header and claims, expired / changed / foreign-secret / unsigned / foreign-issuer / garbage tokens → 401 |
| `RbacTest` | every role and ownership rule, roles from the token, `authorId` refused, role changes |
| `CorsTest` | allowed origin, unknown origin 403, methods / headers, no CSRF token needed for the API |
| `GoogleUserTest`, `GoogleLoginTest` | Google user rules (new / known email, username, no password), the redirect to Google, the success / failure handler, frontend redirect |
| `TokenStoreTest`, `TokenRevocationTest` | blacklist and session maps (unit), logout, revoke on role / password / delete, admin sessions and forced logout |
| `LoginAttemptServiceTest`, `BruteForceTest` | the sliding window with a movable clock, 429 + `Retry-After` over HTTP, another IP not blocked |
| `SecurityMonitorTest`, `SecurityEventsTest` | top-k, endpoint grouping, suspects, exact log lines, log injection, the admin report |
| `OpenApiTest` | bearer scheme, public endpoints without lock, each endpoint's `@PreAuthorize` in the docs |
| `RoleSchemaTest`, `PasswordHashingTest`, `AdminAccountInitializerTest` | roles table and Phase III migration, BCrypt, the first admin account |
| `ApiTest` | every endpoint's behaviour (status codes, JSON shape, paging, strict validation) as a logged-in user |
| `RepositoryTest`, `TransactionTest`, `QueryOptimizationTest`, `CacheTest`, `SoftDeleteTest`, `CustomValidatorsTest` | the Phase III data layer: queries, transactions, N+1 guard, caching, soft delete, custom validators |

Performance tests (on a seeded `blog_db`) run only on purpose:
```bash
./mvnw test -Dtest=PerformanceTest -Dperf=true
./mvnw test -Dtest=PagingStrategiesTest -Dperf=true
```

---

## 8. Project structure

```
src/main/java/com/blogPlatform/III/blog
├── aop/             PerformanceAspect: times every service / repository call
├── config/          CacheConfig, ClockConfig, OpenApiConfig, PasswordConfig (BCrypt)
├── controller/      REST endpoints, incl. AuthController and AdminSecurityController
├── dto/request/     what clients send (records + Bean Validation)
├── dto/response/    what the API returns (ApiResponse, TokenResponse, SecurityReport, ...)
├── entity/          User, Role, RoleName, AuthProvider, Post, Comment, Review, Tag, AuditLog
├── exception/       custom exceptions + GlobalExceptionHandler (incl. 401 / 403 / 429)
├── repository/      Spring Data JPA repositories
├── security/        SecurityConfig (the filter chain), SecurityErrorHandler, AuthUser,
│   │                AppUserDetailsService, Ownership (@PreAuthorize helpers), CorsConfig
│   ├── jwt/         JwtService, JwtAuthenticationFilter, JwtProperties, IssuedToken
│   ├── oauth2/      GoogleLoginHandler, OAuth2Properties
│   ├── token/       TokenBlacklist, ActiveSessionRegistry, ActiveSession, TokenDetails
│   └── monitor/     LoginAttemptService, SecurityMonitor, AccessMonitorFilter, TopK
├── seed/            DataSeeder (profile "seed")
├── service/         business rules, @Transactional, @Cacheable; AuthService (login, tokens, logout)
├── specification/   JPA Specifications for the post filters
├── util/            startup helpers: SchemaHelper, RoleSchemaInitializer, SoftDeleteSchemaInitializer,
│                    FullTextIndexInitializer, MySqlFunctionContributor, AdminAccountInitializer
└── validation/      custom constraints: @ValidPageable, @ValidCursor, @Username, @TagName
src/main/resources
├── application.properties          app.jwt.*, app.admin.*, app.cors.*, app.security.login.*
├── application-google.properties   the Google client (profile "google")
└── logback-spring.xml              logs/security.log
```

## 9. More documentation

| Document | Content |
|---|---|
| [`phases.md`](phases.md) | the nine phases of Phase IV, built from the user stories, with their tasks |
| [`Docs/erd.png`](Docs/erd.png) | the database: users, roles, user_roles, posts, tags, comments, reviews, audit logs |
