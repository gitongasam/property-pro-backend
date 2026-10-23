package com.propertypro.controller;

import com.propertypro.dto.LeasingDtos.TenantListItem;
import com.propertypro.dto.LeasingDtos.TenantRequest;
import com.propertypro.dto.LeasingDtos.TenantResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Tenant;
import com.propertypro.service.TenantService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Tenants")
@RestController
@RequestMapping("/api/v1/tenants")
@RequiredArgsConstructor
public class TenantController {

    private final TenantService tenants;

    @Operation(summary = "List tenants with their current unit and balance; q searches name, phone, account code")
    @GetMapping
    @PreAuthorize("hasAuthority('TENANT_VIEW')")
    public PageResponse<TenantListItem> list(@RequestParam(required = false) String q,
                                             @RequestParam(required = false) Tenant.Status status,
                                             @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return tenants.list(q, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('TENANT_VIEW')")
    public TenantResponse get(@PathVariable UUID id) {
        return tenants.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    public TenantResponse create(@Valid @RequestBody TenantRequest body) {
        return tenants.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    public TenantResponse update(@PathVariable UUID id, @Valid @RequestBody TenantRequest body) {
        return tenants.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('TENANT_MANAGE')")
    public void delete(@PathVariable UUID id) {
        tenants.delete(id);
    }
}
