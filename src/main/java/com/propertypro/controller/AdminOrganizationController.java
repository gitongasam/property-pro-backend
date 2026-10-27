package com.propertypro.controller;

import com.propertypro.dto.AdminDtos.ExtendTrialRequest;
import com.propertypro.dto.AdminDtos.OrganizationDetail;
import com.propertypro.dto.AdminDtos.OrganizationRow;
import com.propertypro.dto.AdminDtos.ReasonRequest;
import com.propertypro.dto.AdminDtos.SuspendRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.service.AdminOrganizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Platform console: every organisation on PropertyPro. Platform accounts only (see SecurityConfig). */
@Tag(name = "Admin: organisations")
@RestController
@RequestMapping("/api/v1/admin/organizations")
@RequiredArgsConstructor
public class AdminOrganizationController {

    private final AdminOrganizationService service;

    @Operation(summary = "Search organisations by name, slug, email or phone; filter by status and plan")
    @GetMapping
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','PLATFORM_BILLING','PLATFORM_SUPPORT')")
    public PageResponse<OrganizationRow> list(@RequestParam(required = false) String q,
                                              @RequestParam(required = false) String status,
                                              @RequestParam(required = false) String plan,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "20") int size) {
        return service.list(q, status, plan, page, size);
    }

    @Operation(summary = "Profile, subscription, usage against plan limits, staff and recent admin actions")
    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','PLATFORM_BILLING','PLATFORM_SUPPORT')")
    public OrganizationDetail get(@PathVariable UUID id) {
        return service.get(id);
    }

    @Operation(summary = "Suspend: its users can only sign in and see the reason; billing jobs skip it")
    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public OrganizationDetail suspend(@PathVariable UUID id, @Valid @RequestBody SuspendRequest body) {
        return service.suspend(id, body.reason());
    }

    @PostMapping("/{id}/reactivate")
    @PreAuthorize("hasAuthority('PLATFORM_ADMIN')")
    public OrganizationDetail reactivate(@PathVariable UUID id, @Valid @RequestBody(required = false) ReasonRequest body) {
        return service.reactivate(id, body == null ? null : body.reason());
    }

    @Operation(summary = "Add 1-90 days to the trial (from now if it has already ended)")
    @PostMapping("/{id}/extend-trial")
    @PreAuthorize("hasAnyAuthority('PLATFORM_ADMIN','PLATFORM_BILLING')")
    public OrganizationDetail extendTrial(@PathVariable UUID id, @Valid @RequestBody ExtendTrialRequest body) {
        return service.extendTrial(id, body.days(), body.reason());
    }
}
