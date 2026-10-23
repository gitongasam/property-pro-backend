package com.propertypro.service;

import com.propertypro.dto.LeasingDtos.TenantListItem;
import com.propertypro.dto.LeasingDtos.TenantRequest;
import com.propertypro.dto.LeasingDtos.TenantResponse;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Tenant;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface TenantService {

    PageResponse<TenantListItem> list(String q, Tenant.Status status, Pageable pageable);

    TenantResponse get(UUID id);

    TenantResponse create(TenantRequest req);

    /** Used by the API and by imports. */
    Tenant createEntity(TenantRequest req);

    TenantResponse update(UUID id, TenantRequest req);

    void delete(UUID id);

    Tenant find(UUID id);
}
