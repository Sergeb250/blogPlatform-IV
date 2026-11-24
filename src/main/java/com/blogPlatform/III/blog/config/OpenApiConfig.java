package com.blogPlatform.III.blog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Operation;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springdoc.core.customizers.OperationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.prepost.PreAuthorize;

import java.util.ArrayList;

@Configuration
public class OpenApiConfig {

    static final String BEARER = "bearerAuth";
    private static final String ACCESS = "**Access:** ";

    @Bean
    public OpenAPI blogPlatformOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("Blogging Platform API")
                        .version("4.0")
                        .description("""
                                REST API for users, posts, tags, comments and reviews, secured with Spring Security.

                                **Log in first:** `POST /api/auth/register`, then `POST /api/auth/login` returns an
                                `accessToken` (a JWT, valid 60 minutes). Click **Authorize** and paste it: every request then
                                sends `Authorization: Bearer <token>`. Endpoints without a lock are public.
                                Google login: open `/oauth2/authorization/google` in a browser (profile `google`).

                                **Who may call what:** every operation ends with its **Access** rule, the `@PreAuthorize`
                                expression of the endpoint. Roles: `READER` (comment, review), `AUTHOR` (+ write posts,
                                create tags), `ADMIN` (everything). `@ownership...` = only on your own posts / comments / account.

                                **Security answers:** `401` no token, or an expired / changed / revoked one · `403` logged in
                                but not allowed · `429` too many failed logins (see `Retry-After`).

                                **Every response has the same shape:**
                                ```
                                { "success": true,  "status": 200, "message": "...", "data": { ... }, "timestamp": "..." }
                                { "success": false, "status": 404, "message": "...", "errors": { ... }, "path": "...", "timestamp": "..." }
                                ```
                                **Every list is paged** (`page`, `size` 1-100, `sort` from a whitelist); the feed
                                (`/api/posts/feed`) pages with a cursor. **Strict validation:** unknown or wrongly typed JSON
                                fields are refused (400). **Optimistic locking:** `PUT` must send the `version` it read (old -> 409).
                                """))
                .components(new Components().addSecuritySchemes(BEARER, new SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description("The accessToken from POST /api/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER));
    }

    // Every operation ends with who may call it: its @PreAuthorize rule, or "public" (no lock) when it has none.
    // The endpoints without a @PreAuthorize are exactly the ones SecurityConfig opens: sign-up, login and GET reads.
    @Bean
    public OperationCustomizer accessRules() {
        return (operation, handlerMethod) -> {
            PreAuthorize rule = handlerMethod.getMethodAnnotation(PreAuthorize.class);
            if (rule == null) {
                rule = handlerMethod.getBeanType().getAnnotation(PreAuthorize.class);
            }
            if (rule == null) {
                operation.setSecurity(new ArrayList<>());
                appendAccess(operation, "public, no token needed");
            } else {
                appendAccess(operation, "`" + rule.value() + "`");
            }
            return operation;
        };
    }

    private static void appendAccess(Operation operation, String rule) {
        String description = operation.getDescription() == null ? "" : operation.getDescription() + "\n\n";
        operation.setDescription(description + ACCESS + rule);
    }
}
