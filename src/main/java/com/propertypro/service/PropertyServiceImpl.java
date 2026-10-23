package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.PropertyDetailResponse;
import com.propertypro.dto.PortfolioDtos.PropertyRequest;
import com.propertypro.dto.PortfolioDtos.PropertyResponse;
import com.propertypro.dto.PortfolioDtos.PropertyStats;
import com.propertypro.entity.Owner;
import com.propertypro.entity.Property;
import com.propertypro.entity.Unit;
import com.propertypro.repository.PropertyRepository;
import com.propertypro.repository.UnitRepository;
import com.propertypro.repository.spec.PortfolioSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PropertyServiceImpl implements PropertyService {

    private final PropertyRepository properties;
    private final UnitRepository units;
    private final OwnerService ownerService;
    private final PlanGate planGate;
    private final JdbcClient jdbc;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<PropertyResponse> list(String q, Property.Type type, UUID ownerId,
                                               Property.Status status, Pageable pageable) {
        var spec = Specification.allOf(
                PortfolioSpecs.propertyMatches(q),
                PortfolioSpecs.propertyType(type),
                PortfolioSpecs.propertyOwner(ownerId),
                PortfolioSpecs.propertyStatus(status));
        return PageResponse.of(properties.findAll(spec, pageable), PropertyResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public PropertyDetailResponse get(UUID id) {
        Property property = find(id);
        return new PropertyDetailResponse(PropertyResponse.from(property), stats(id));
    }

    @Override
    @Transactional
    public PropertyResponse create(PropertyRequest req) {
        planGate.requireWritable();
        if (req.code() == null || req.code().isBlank()) {
            throw ApiException.badRequest("CODE_REQUIRED", "A short property code is required, e.g. KLM");
        }
        String code = req.code().trim().toUpperCase(Locale.ROOT);
        if (properties.codeExists(code)) {
            throw ApiException.conflict("PROPERTY_CODE_TAKEN", "A property with code " + code + " already exists");
        }
        Property property = new Property();
        property.setCode(code);
        apply(property, req);
        return PropertyResponse.from(properties.save(property));
    }

    @Override
    @Transactional
    public PropertyResponse update(UUID id, PropertyRequest req) {
        planGate.requireWritable();
        Property property = find(id);
        apply(property, req);
        return PropertyResponse.from(property);
    }

    @Override
    @Transactional
    public PropertyResponse setStatus(UUID id, Property.Status status) {
        planGate.requireWritable();
        Property property = find(id);
        property.setStatus(status);
        return PropertyResponse.from(property);
    }

    /** Archives the property and its units. Refused while any unit is occupied. */
    @Override
    @Transactional
    public void delete(UUID id) {
        planGate.requireWritable();
        Property property = find(id);
        if (units.countByPropertyIdAndStatus(id, Unit.Status.OCCUPIED) > 0) {
            throw ApiException.conflict("PROPERTY_HAS_TENANTS",
                    "End the active leases in this property before removing it");
        }
        units.softDeleteByProperty(id, OffsetDateTime.now());
        property.softDelete();
    }

    @Override
    public Property find(UUID id) {
        return properties.findById(id).orElseThrow(() -> ApiException.notFound("Property"));
    }

    /** Occupancy figures from the reporting view (RLS applies through security_invoker). */
    private PropertyStats stats(UUID propertyId) {
        return jdbc.sql("""
                        SELECT total_units, occupied_units, vacant_units, occupancy_pct,
                               potential_monthly_rent, actual_monthly_rent, vacancy_loss
                          FROM v_property_occupancy
                         WHERE property_id = ?
                        """)
                .param(propertyId)
                .query((rs, rowNum) -> new PropertyStats(
                        rs.getLong("total_units"),
                        rs.getLong("occupied_units"),
                        rs.getLong("vacant_units"),
                        rs.getBigDecimal("occupancy_pct"),
                        rs.getBigDecimal("potential_monthly_rent"),
                        rs.getBigDecimal("actual_monthly_rent"),
                        rs.getBigDecimal("vacancy_loss")))
                .optional()
                .orElse(PropertyStats.EMPTY);
    }

    private void apply(Property p, PropertyRequest r) {
        p.setOwner(r.ownerId() == null ? null : ownerService.find(r.ownerId()));
        p.setName(r.name().trim());
        p.setPropertyType(r.propertyType());
        p.setCounty(r.county());
        p.setTown(r.town());
        p.setEstate(r.estate());
        p.setAddress(r.address());
        p.setLatitude(r.latitude());
        p.setLongitude(r.longitude());
        p.setLrNumber(r.lrNumber());
        p.setYearBuilt(r.yearBuilt());
        p.setFloors(r.floors());
        p.setDescription(r.description());
        p.setAmenities(r.amenities() == null ? new ArrayList<>() : new ArrayList<>(r.amenities()));
        if (r.managementFeeType() == Owner.FeeType.PERCENT && r.managementFeeValue() != null
                && r.managementFeeValue().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("INVALID_FEE", "A percentage management fee cannot exceed 100");
        }
        p.setManagementFeeType(r.managementFeeType());
        p.setManagementFeeValue(r.managementFeeType() == null ? null : r.managementFeeValue());
    }
}
