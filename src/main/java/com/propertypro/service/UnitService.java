package com.propertypro.service;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.dto.PortfolioDtos.UnitResponse;
import com.propertypro.entity.Unit;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.UUID;

public interface UnitService {

    PageResponse<UnitResponse> list(UUID propertyId, Unit.Status status, Unit.Type type, String q, Pageable pageable);

    UnitResponse get(UUID unitId);

    UnitResponse create(UUID propertyId, UnitRequest req);

    /**
     * Creates many units in one go (e.g. a new block of 40 flats). All or nothing:
     * any duplicate number or payment code, or passing the plan's unit limit, rejects the batch.
     */
    List<UnitResponse> createAll(UUID propertyId, List<UnitRequest> requests);

    UnitResponse update(UUID unitId, UnitRequest req);

    UnitResponse setStatus(UUID unitId, Unit.Status status);

    void delete(UUID unitId);

    Unit find(UUID unitId);
}
