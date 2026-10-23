package com.propertypro.service;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.PropertyDetailResponse;
import com.propertypro.dto.PortfolioDtos.PropertyRequest;
import com.propertypro.dto.PortfolioDtos.PropertyResponse;
import com.propertypro.entity.Property;
import org.springframework.data.domain.Pageable;

import java.util.UUID;

public interface PropertyService {

    PageResponse<PropertyResponse> list(String q, Property.Type type, UUID ownerId, Property.Status status, Pageable pageable);

    PropertyDetailResponse get(UUID id);

    PropertyResponse create(PropertyRequest req);

    PropertyResponse update(UUID id, PropertyRequest req);

    PropertyResponse setStatus(UUID id, Property.Status status);

    /** Archives the property and its units. Refused while any unit is occupied. */
    void delete(UUID id);

    Property find(UUID id);
}
