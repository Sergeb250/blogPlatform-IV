package com.blogPlatform.III.blog;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// the generated OpenAPI document: the login scheme, which endpoints are public, and each endpoint's access rule
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpenApiTest {

    @Autowired MockMvc mockMvc;

    @Test
    void theDocs_describeTheBearerLogin() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.type").value("http"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.scheme").value("bearer"))
                .andExpect(jsonPath("$.components.securitySchemes.bearerAuth.bearerFormat").value("JWT"))
                // by default every operation needs the token
                .andExpect(jsonPath("$.security[0].bearerAuth").isArray());
    }

    @Test
    void publicEndpoints_haveNoLock_likeInSecurityConfig() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/register'].post.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/posts'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}/comments'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/tags'].get.security").isEmpty())
                .andExpect(jsonPath("$.paths['/api/posts'].get.description",
                        endsWith("**Access:** public, no token needed")))
                // writing is not public: no own "security" entry, so the global bearer requirement applies
                .andExpect(jsonPath("$.paths['/api/posts'].post.security").doesNotExist());
    }

    @Test
    void everyProtectedEndpoint_showsItsPreAuthorizeRule() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(jsonPath("$.paths['/api/posts'].post.description",
                        endsWith("**Access:** `hasAnyRole('AUTHOR', 'ADMIN')`")))
                .andExpect(jsonPath("$.paths['/api/posts/{id}'].put.description",
                        endsWith("**Access:** `hasRole('ADMIN') or (hasRole('AUTHOR') and @ownership.isPostAuthor(#id, authentication))`")))
                .andExpect(jsonPath("$.paths['/api/users'].get.description", endsWith("**Access:** `hasRole('ADMIN')`")))
                // a class-level rule counts too
                .andExpect(jsonPath("$.paths['/api/admin/security/report'].get.description",
                        endsWith("**Access:** `hasRole('ADMIN')`")))
                .andExpect(jsonPath("$.paths['/api/auth/me'].get.description", endsWith("**Access:** `isAuthenticated()`")));
    }
}
