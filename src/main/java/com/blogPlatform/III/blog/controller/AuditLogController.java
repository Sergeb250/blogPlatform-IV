package com.blogPlatform.III.blog.controller;

import com.blogPlatform.III.blog.dto.response.ApiResponse;
import com.blogPlatform.III.blog.dto.response.AuditLogResponse;
import com.blogPlatform.III.blog.dto.response.PageResponse;
import com.blogPlatform.III.blog.service.AuditService;
import com.blogPlatform.III.blog.validation.ValidPageable;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Audit log", description = "Every write attempt on a post, also the failed ones")
@RestController
@RequestMapping("/api/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditService auditService;

    @Operation(summary = "List audit log entries (ADMIN)",
            description = "Newest first, 20 per page (size 1-100). Sort fields: id, action, createdAt. Entries are written in their own "
                    + "transaction (REQUIRES_NEW), so they stay even when the action was rolled back. 403: not an admin.")
    @PreAuthorize("hasRole('ADMIN')")
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<AuditLogResponse>>> getAll(
            @ParameterObject
            @ValidPageable(sortFields = {"id", "action", "createdAt"})
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResponse.ok("Audit logs retrieved successfully", auditService.getAll(pageable));
    }
}
