package com.propertypro.service;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.OwnerRequest;
import com.propertypro.dto.PortfolioDtos.OwnerResponse;
import com.propertypro.entity.Owner;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface OwnerService {

    PageResponse<OwnerResponse> list(String q, Pageable pageable);

    OwnerResponse get(UUID id);

    OwnerResponse create(OwnerRequest req);

    OwnerResponse update(UUID id, OwnerRequest req);

    void delete(UUID id);

    Owner find(UUID id);
}
