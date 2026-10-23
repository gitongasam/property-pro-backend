package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.Area;
import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.dto.PortfolioDtos.UnitResponse;
import com.propertypro.entity.Property;
import com.propertypro.entity.Unit;
import com.propertypro.repository.UnitRepository;
import com.propertypro.repository.spec.PortfolioSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UnitServiceImpl implements UnitService {

    /** Statuses staff may set by hand. OCCUPIED is set and cleared only by the leasing module. */
    private static final Set<Unit.Status> MANUAL_STATUSES = EnumSet.of(
            Unit.Status.VACANT, Unit.Status.RESERVED, Unit.Status.UNDER_MAINTENANCE, Unit.Status.UNAVAILABLE);

    private final UnitRepository units;
    private final PropertyService propertyService;
    private final PlanGate planGate;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<UnitResponse> list(UUID propertyId, Unit.Status status, Unit.Type type, String q,
                                           Pageable pageable) {
        propertyService.find(propertyId);   // 404 if the property is not ours
        var spec = Specification.allOf(
                PortfolioSpecs.unitInProperty(propertyId),
                PortfolioSpecs.unitStatus(status),
                PortfolioSpecs.unitType(type),
                PortfolioSpecs.unitMatches(q));
        return PageResponse.of(units.findAll(spec, pageable), UnitResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public UnitResponse get(UUID unitId) {
        return UnitResponse.from(find(unitId));
    }

    @Override
    @Transactional
    public UnitResponse create(UUID propertyId, UnitRequest req) {
        return createAll(propertyId, List.of(req)).getFirst();
    }

    /**
     * Creates many units in one go (e.g. a new block of 40 flats). All or nothing:
     * any duplicate number or payment code, or passing the plan's unit limit, rejects the batch.
     */
    @Override
    @Transactional
    public List<UnitResponse> createAll(UUID propertyId, List<UnitRequest> requests) {
        planGate.requireWritable();
        Property property = propertyService.find(propertyId);
        planGate.requireUnitCapacity(units.count(), requests.size());

        List<Unit> toSave = new ArrayList<>(requests.size());
        Set<String> unitNos = new LinkedHashSet<>();
        Set<String> paymentCodes = new LinkedHashSet<>();
        for (UnitRequest req : requests) {
            Unit unit = new Unit();
            unit.setProperty(property);
            apply(unit, req, property);
            if (!unitNos.add(unit.getUnitNo().toUpperCase(Locale.ROOT))) {
                throw ApiException.badRequest("DUPLICATE_UNIT_NO", "Unit " + unit.getUnitNo() + " appears twice");
            }
            if (!paymentCodes.add(unit.getPaymentCode())) {
                throw ApiException.badRequest("DUPLICATE_PAYMENT_CODE",
                        "Payment code " + unit.getPaymentCode() + " appears twice");
            }
            toSave.add(unit);
        }

        List<String> takenNos = units.findExistingUnitNos(propertyId, unitNos);
        if (!takenNos.isEmpty()) {
            throw ApiException.conflict("UNIT_NO_TAKEN",
                    "These unit numbers already exist in " + property.getName() + ": " + String.join(", ", takenNos));
        }
        List<String> takenCodes = units.findExistingPaymentCodes(paymentCodes);
        if (!takenCodes.isEmpty()) {
            throw ApiException.conflict("PAYMENT_CODE_TAKEN",
                    "These payment codes are already used: " + String.join(", ", takenCodes));
        }

        return units.saveAll(toSave).stream().map(UnitResponse::from).toList();
    }

    @Override
    @Transactional
    public UnitResponse update(UUID unitId, UnitRequest req) {
        planGate.requireWritable();
        Unit unit = find(unitId);
        Property property = unit.getProperty();

        // Check for clashes BEFORE changing the entity: Hibernate flushes pending
        // changes before running a query, so checking afterwards would find the unit itself.
        String newNo = req.unitNo().trim().toUpperCase(Locale.ROOT);
        String newCode = paymentCodeFor(req, property);
        if (!newNo.equals(unit.getUnitNo().toUpperCase(Locale.ROOT))
                && !units.findExistingUnitNos(property.getId(), Set.of(newNo)).isEmpty()) {
            throw ApiException.conflict("UNIT_NO_TAKEN", "Unit " + req.unitNo().trim() + " already exists in this property");
        }
        if (!newCode.equals(unit.getPaymentCode())
                && !units.findExistingPaymentCodes(Set.of(newCode)).isEmpty()) {
            throw ApiException.conflict("PAYMENT_CODE_TAKEN", "Payment code " + newCode + " is already used");
        }

        apply(unit, req, property);
        return UnitResponse.from(unit);
    }

    @Override
    @Transactional
    public UnitResponse setStatus(UUID unitId, Unit.Status status) {
        planGate.requireWritable();
        Unit unit = find(unitId);
        if (!MANUAL_STATUSES.contains(status)) {
            throw ApiException.badRequest("STATUS_SET_BY_LEASE", "A unit becomes occupied when a lease is activated");
        }
        if (unit.getStatus() == Unit.Status.OCCUPIED) {
            throw ApiException.conflict("UNIT_OCCUPIED", "End the lease before changing an occupied unit's status");
        }
        unit.setStatus(status);
        return UnitResponse.from(unit);
    }

    @Override
    @Transactional
    public void delete(UUID unitId) {
        planGate.requireWritable();
        Unit unit = find(unitId);
        if (unit.getStatus() == Unit.Status.OCCUPIED) {
            throw ApiException.conflict("UNIT_OCCUPIED", "End the lease before removing this unit");
        }
        unit.softDelete();
    }

    @Override
    public Unit find(UUID unitId) {
        return units.findById(unitId).orElseThrow(() -> ApiException.notFound("Unit"));
    }

    private static void apply(Unit u, UnitRequest r, Property property) {
        u.setUnitNo(r.unitNo().trim());
        u.setBlock(r.block());
        u.setFloor(r.floor());
        u.setUnitType(r.unitType());
        u.setBedrooms(r.bedrooms());
        u.setBathrooms(r.bathrooms());
        u.setSizeSqft(r.sizeSqft());
        u.setRentPerSqft(r.rentPerSqft());
        u.setServiceChargePerSqft(r.serviceChargePerSqft());
        if ((r.rentPerSqft() != null || r.serviceChargePerSqft() != null) && r.sizeSqft() == null) {
            throw ApiException.badRequest("SIZE_REQUIRED", "Unit " + r.unitNo().trim() + ": give the size in sq ft to charge per sq ft");
        }
        if (r.rentPerSqft() != null) {
            u.setMarketRent(Area.amount(r.sizeSqft(), r.rentPerSqft(), 1));
        } else if (r.marketRent() != null) {
            u.setMarketRent(r.marketRent());
        } else {
            throw ApiException.badRequest("RENT_REQUIRED", "Unit " + r.unitNo().trim() + ": give the rent or a rent per sq ft");
        }
        u.setDepositAmount(r.depositAmount() == null ? BigDecimal.ZERO : r.depositAmount());
        if (r.serviceChargePerSqft() != null) {
            u.setServiceCharge(Area.amount(r.sizeSqft(), r.serviceChargePerSqft(), 1));
        } else {
            u.setServiceCharge(r.serviceCharge() == null ? BigDecimal.ZERO : r.serviceCharge());
        }
        u.setPaymentCode(paymentCodeFor(r, property));
        u.setWaterMeterNo(r.waterMeterNo());
        u.setElectricityMeterNo(r.electricityMeterNo());
        u.setNotes(r.notes());
    }

    private static String paymentCodeFor(UnitRequest r, Property property) {
        return r.paymentCode() == null || r.paymentCode().isBlank()
                ? defaultPaymentCode(property.getCode(), r.unitNo())
                : r.paymentCode().trim().toUpperCase(Locale.ROOT);
    }

    /** KLM + "B 4" -> KLM-B4; what tenants type as the Paybill account number. */
    static String defaultPaymentCode(String propertyCode, String unitNo) {
        String unitPart = unitNo.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        String code = propertyCode.toUpperCase(Locale.ROOT) + "-" + unitPart;
        return code.length() > 30 ? code.substring(0, 30) : code;
    }
}
