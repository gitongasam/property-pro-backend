package com.propertypro.controller;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.BulkUnitsRequest;
import com.propertypro.dto.PortfolioDtos.PropertyDetailResponse;
import com.propertypro.dto.PortfolioDtos.PropertyRequest;
import com.propertypro.dto.PortfolioDtos.PropertyResponse;
import com.propertypro.dto.PortfolioDtos.PropertyStatusRequest;
import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.dto.PortfolioDtos.UnitResponse;
import com.propertypro.entity.Property;
import com.propertypro.entity.Unit;
import com.propertypro.service.PropertyService;
import com.propertypro.service.UnitService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@Tag(name = "Properties")
@RestController
@RequestMapping("/api/v1/properties")
@RequiredArgsConstructor
public class PropertyController {

    private final PropertyService propertyService;
    private final UnitService unitService;

    @Operation(summary = "List properties; filter by text, type, owner or status")
    @GetMapping
    @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
    public PageResponse<PropertyResponse> list(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) Property.Type type,
                                               @RequestParam(required = false) UUID ownerId,
                                               @RequestParam(required = false) Property.Status status,
                                               @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return propertyService.list(q, type, ownerId, status, pageable);
    }

    @Operation(summary = "Property with occupancy, rent roll and vacancy figures")
    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_VIEW')")
    public PropertyDetailResponse get(@PathVariable UUID id) {
        return propertyService.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PROPERTY_MANAGE')")
    public PropertyResponse create(@Valid @RequestBody PropertyRequest body) {
        return propertyService.create(body);
    }

    @Operation(summary = "Update a property (the code cannot be changed)")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PROPERTY_MANAGE')")
    public PropertyResponse update(@PathVariable UUID id, @Valid @RequestBody PropertyRequest body) {
        return propertyService.update(id, body);
    }

    @PatchMapping("/{id}/status")
    @PreAuthorize("hasAuthority('PROPERTY_MANAGE')")
    public PropertyResponse setStatus(@PathVariable UUID id, @Valid @RequestBody PropertyStatusRequest body) {
        return propertyService.setStatus(id, body.status());
    }

    @Operation(summary = "Archive a property and its units (refused while any unit is occupied)")
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PROPERTY_MANAGE')")
    public void delete(@PathVariable UUID id) {
        propertyService.delete(id);
    }

    // ------------------------------------------------------------ units

    @Operation(summary = "Units in a property; filter by status, type or text")
    @GetMapping("/{propertyId}/units")
    @PreAuthorize("hasAuthority('UNIT_VIEW')")
    public PageResponse<UnitResponse> units(@PathVariable UUID propertyId,
                                            @RequestParam(required = false) Unit.Status status,
                                            @RequestParam(required = false) Unit.Type type,
                                            @RequestParam(required = false) String q,
                                            @PageableDefault(size = 50, sort = "unitNo") Pageable pageable) {
        return unitService.list(propertyId, status, type, q, pageable);
    }

    @PostMapping("/{propertyId}/units")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public UnitResponse addUnit(@PathVariable UUID propertyId, @Valid @RequestBody UnitRequest body) {
        return unitService.create(propertyId, body);
    }

    @Operation(summary = "Add up to 500 units at once (all or nothing)")
    @PostMapping("/{propertyId}/units/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('UNIT_MANAGE')")
    public List<UnitResponse> addUnits(@PathVariable UUID propertyId, @Valid @RequestBody BulkUnitsRequest body) {
        return unitService.createAll(propertyId, body.units());
    }
}
