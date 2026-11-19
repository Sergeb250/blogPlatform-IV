# Blogging Platform IV – Implementation Phases

Phase IV secures the Phase III REST API: Spring Security, JWT login, Google login, role-based access (RBAC),
CORS / CSRF, and security-focused data structures (hashing, token blacklist, session map, brute-force window).

Every phase is built from the user stories of the project brief. When a phase is done, its code is explained in
`Docs/explained/phase-N-*.md` (what was built, how it works, why it was done that way, with examples).

| # | Phase | User stories | Status | Explanation |
|---|---|---|---|---|
| 1 | Users, roles and BCrypt | Tech. requirements, 1.1 (passwords) | Done | [phase-1](Docs/explained/phase-1-users-roles-bcrypt.md) |
| 2 | Security filter chain and access rules | 1.1 | Done | [phase-2](Docs/explained/phase-2-security-filter-chain.md) |
| 3 | JWT authentication | 2.1, 2.2 | Done | [phase-3](Docs/explained/phase-3-jwt-authentication.md) |
| 4 | Role-based access control (RBAC) | 4.2 | Done | [phase-4](Docs/explained/phase-4-rbac.md) |
| 5 | CORS and CSRF | 1.2, 3.1, 3.2 | Done | [phase-5](Docs/explained/phase-5-cors-csrf.md) |
| 6 | Google OAuth2 login | 4.1 | Done | [phase-6](Docs/explained/phase-6-google-oauth2.md) |
| 7 | DSA: token blacklist, sessions, hashing | 5.1 | Done | [phase-7](Docs/explained/phase-7-dsa-tokens.md) |
| 8 | Security event logging and monitoring | 5.2 | Done | [phase-8](Docs/explained/phase-8-security-monitoring.md) |
| 9 | OpenAPI, Postman, README, tests | Deliverables | Done | [phase-9](Docs/explained/phase-9-docs-and-testing.md) |

---

## Phase 1 – Users, roles and BCrypt

**Goal:** the database knows *who* a user is and *what roles* they have, and passwords are stored safely.

**Tasks**
- [x] Java 21 (`pom.xml`)
- [x] `Role` becomes an entity (`roles` table) with a `RoleName` enum: `ADMIN`, `AUTHOR`, `READER`
- [x] `User` ↔ `Role` many-to-many through `user_roles`
- [x] `User` gets `auth_provider` (`LOCAL` / `GOOGLE`) and `provider_id`; `password_hash` may be empty (Google accounts)
- [x] One-time migration of the Phase III `users.role` column into `user_roles` (`RoleSchemaInitializer`)
- [x] `BCryptPasswordEncoder` as a Spring bean (`PasswordConfig`), replacing the static `PasswordUtil`
- [x] Fix: `UserService.create` saved an empty user (the copy line was commented out)
- [x] Seeder writes roles through `user_roles`
- [x] Fix: `RepositoryTest` called three `PostRepository` methods that were commented out (tests did not compile)
- [x] New ERD (`Docs/erd.png`); the old Phase III docs leave `Docs/`

**Done when:** the app starts on an old Phase III database, every old user keeps their role, and new passwords are BCrypt hashes.

---

## Phase 2 – Security filter chain and access rules

**Story 1.1:** *configure Spring Security filters to protect all endpoints and define which resources need a login.*

**Tasks**
- [x] Dependency `spring-boot-starter-security` (+ `spring-boot-starter-security-test`)
- [x] `SecurityConfig` with a custom `SecurityFilterChain`
- [x] Public: sign-up (`POST /api/users`), Swagger, all `GET` on posts and tags (and their comments / reviews); the login and Google paths are added in Phases 3 and 6
- [x] Everything else needs a login; `/api/admin/**` needs `ADMIN`
- [x] Stateless sessions, no HTTP Basic, no form login
- [x] `AppUserDetailsService` + `AuthenticationManager` (DAO provider + BCrypt)
- [x] 401 / 403 answered in the API's normal JSON shape (`SecurityErrorHandler`)

**Done when:** an anonymous call to a protected endpoint gets a JSON 401, a public `GET` still works.

---

## Phase 3 – JWT authentication

**Story 2.1:** *log in and receive a JWT.* **Story 2.2:** *verify token structure and claims.*

**Tasks**
- [x] Dependency `jjwt` 0.12.6 (`jjwt-api`, `jjwt-impl`, `jjwt-jackson`)
- [x] `POST /api/auth/register` (replaces the public `POST /api/users`), `POST /api/auth/login`, `GET /api/auth/me`
- [x] Sign-up always gives READER; a `role` field in the body is refused (400)
- [x] `JwtService`: HS256 signature, claims `sub`, `uid`, `roles`, `iat`, `exp`, `jti`, `iss`
- [x] `JwtAuthenticationFilter`: reads `Authorization: Bearer …`, verifies, sets the logged-in user
- [x] Expired, tampered, unsigned (`alg: none`) or malformed tokens → 401 with a clear message
- [x] Secret and lifetime from configuration (`JWT_SECRET`, `app.jwt.expiration`)

**Done when:** login returns a token that decodes on jwt.io / in Postman, and a changed or expired token is refused.

---

## Phase 4 – Role-based access control (RBAC)

**Story 4.2:** *restrict endpoints by role.*

**Tasks**
- [x] `@EnableMethodSecurity` and `@PreAuthorize` on every protected endpoint; a refused `@PreAuthorize` is a JSON 403
- [x] Ownership checks (`Ownership` bean): authors change only their own posts, users only their own comments / reviews;
      ADMIN may change everything; a missing post / comment / review is 404 (not 403)
- [x] The acting user comes from the token: `authorId` / `userId` removed from request bodies (sending one → 400)
- [x] `PUT /api/users/{id}/roles` (ADMIN) to give or take roles; an admin can't remove their own ADMIN role (409)
- [x] First admin account created at startup (`AdminAccountInitializer`, `ADMIN_USERNAME` / `ADMIN_EMAIL` /
      `ADMIN_PASSWORD`); only while no admin exists, never by promoting an existing account
- [x] Users see and change only their own account; the user list, audit logs and cache stats are ADMIN only
- [x] Access matrix in the README

**Done when:** READER → 403 on creating a post, AUTHOR → 403 on someone else's post, ADMIN → allowed.

---

## Phase 5 – CORS and CSRF

**Story 1.2:** *safe cross-origin requests.* **Story 3.1:** *configure CSRF correctly.* **Story 3.2:** *document CORS vs CSRF.*

**Tasks**
- [x] Global CORS configuration (`CorsConfig`): allowed origins from `app.cors.allowed-origins` (`CORS_ALLOWED_ORIGINS`),
      methods `GET POST PUT PATCH DELETE`, headers `Authorization` + `Content-Type`, preflight cached 1 h, no credentials
- [x] Unknown origins (and methods / headers that are not allowed) blocked with 403; a preflight needs no token
- [x] CSRF off for the stateless JWT API (with the reason, in `SecurityConfig`)
- [x] ~~A second filter chain `/csrf-demo/**` with a session and CSRF on~~: removed on request (one API chain only);
      the CSRF token mechanism for cookie-based apps is explained in the README instead
- [x] Session cookie `HttpOnly` + `SameSite=Lax` (the Google login round trip is the only session)
- [x] README section "CORS vs CSRF"

**Done when:** the preflight from `http://localhost:3000` passes, one from `http://evil.example` gets 403,
the demo form works with the token and fails without it.

---

## Phase 6 – Google OAuth2 login

**Story 4.1:** *log in with Google without registering.*

**Tasks**
- [x] Dependency `spring-boot-starter-oauth2-client`, enabled with the `google` profile (`GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`)
- [x] After Google: find the user by verified email or create one (`GOOGLE`, role `READER`)
- [x] Unverified Google emails are refused; a new user gets a username from the email (`maria.lopez`, `maria-2`, ...)
- [x] Answer with our own JWT (JSON, or a redirect to a frontend)

**Done when:** `/oauth2/authorization/google` sends the browser to Google, and the callback creates the user and returns a token.

---

## Phase 7 – DSA: token blacklist, sessions, hashing

**Story 5.1:** *hashing, token blacklisting and an in-memory map for revoked tokens / active sessions.*

**Tasks**
- [x] `TokenBlacklist`: `ConcurrentHashMap<tokenId, expiry>`, O(1) check on every request, expired entries purged every minute
- [x] `ActiveSessionRegistry`: token id → session, user id → token ids (log a user out everywhere)
- [x] `POST /api/auth/logout` revokes the current token
- [x] Role change, profile change and account delete revoke all tokens of that user
- [x] Admin: list sessions, force-logout a user
- [x] Hashing explained: BCrypt (passwords), HMAC-SHA256 (token signature), hash maps (lookups)

**Done when:** a token used after logout or after a role change is refused with 401 "revoked".

---

## Phase 8 – Security event logging and monitoring

**Story 5.2:** *monitor login attempts and access patterns to find suspicious activity.*

**Tasks**
- [x] `SECURITY` logger → `logs/security.log` (one `key=value` line per event)
- [x] Events: login success / failure / blocked, token rejected, 401, 403, logout, tokens revoked
- [x] `LoginAttemptService`: sliding window per `username|ip`, 5 failures in 15 min → 429 + `Retry-After`
- [x] `SecurityMonitor`: counters per user, IP and endpoint; top-k with a min-heap
- [x] `GET /api/admin/security/report`: token usage, access frequency, suspected brute force

**Done when:** six wrong passwords in a row get 429, and the report names that user and IP.

---

## Phase 9 – OpenAPI, Postman, README, tests

**Tasks**
- [x] OpenAPI: bearer scheme, public endpoints shown without the lock, each operation lists its access rule
- [x] Postman collection with tests for every role (`postman/blog-platform-IV-security.postman_collection.json`): 38 requests, 7 folders; the Phase III collections removed
- [x] README: setup, access matrix, JWT flow, Google setup, CORS vs CSRF, DSA, logs
- [x] Automated tests for every phase; the old tests updated to send tokens

**Done when:** `mvnw test` is green and the Postman collection runs without failures.

Status: `mvnw test` green (200 tests). The collection is checked automatically (valid JSON, every script compiles,
every variable defined) but still needs one live run in Postman, with `ADMIN_PASSWORD` set.
