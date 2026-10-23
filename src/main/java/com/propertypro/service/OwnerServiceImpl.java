package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PortfolioDtos.OwnerRequest;
import com.propertypro.dto.PortfolioDtos.OwnerResponse;
import com.propertypro.entity.Owner;
import com.propertypro.repository.OwnerRepository;
import com.propertypro.repository.PropertyRepository;
import com.propertypro.repository.spec.PortfolioSpecs;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OwnerServiceImpl implements OwnerService {

    private final OwnerRepository owners;
    private final PropertyRepository properties;
    private final PlanGate planGate;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<OwnerResponse> list(String q, Pageable pageable) {
        return PageResponse.of(owners.findAll(Specification.allOf(PortfolioSpecs.ownerMatches(q)), pageable),
                OwnerResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public OwnerResponse get(UUID id) {
        return OwnerResponse.from(find(id));
    }

    @Override
    @Transactional
    public OwnerResponse create(OwnerRequest req) {
        planGate.requireWritable();
        Owner owner = new Owner();
        apply(owner, req);
        return OwnerResponse.from(owners.save(owner));
    }

    @Override
    @Transactional
    public OwnerResponse update(UUID id, OwnerRequest req) {
        planGate.requireWritable();
        Owner owner = find(id);
        apply(owner, req);
        return OwnerResponse.from(owner);
    }

    @Override
    @Transactional
    public void delete(UUID id) {
        planGate.requireWritable();
        Owner owner = find(id);
        if (properties.countByOwnerId(id) > 0) {
            throw ApiException.conflict("OWNER_HAS_PROPERTIES",
                    "Move or archive this owner's properties before removing the owner");
        }
        owner.softDelete();
    }

    @Override
    public Owner find(UUID id) {
        return owners.findById(id).orElseThrow(() -> ApiException.notFound("Owner"));
    }

    private static void apply(Owner o, OwnerRequest r) {
        o.setOwnerType(r.ownerType() == null ? Owner.Type.INDIVIDUAL : r.ownerType());
        o.setName(r.name().trim());
        o.setIdNumber(blankToNull(r.idNumber()));
        o.setKraPin(r.kraPin() == null ? null : r.kraPin().toUpperCase(Locale.ROOT));
        o.setEmail(r.email() == null ? null : r.email().trim().toLowerCase(Locale.ROOT));
        o.setPhone(r.phone());
        o.setAddress(r.address());
        o.setBankName(r.bankName());
        o.setBankBranch(r.bankBranch());
        o.setBankAccountName(r.bankAccountName());
        o.setMpesaPhone(r.mpesaPhone());
        o.setManagementFeeType(r.managementFeeType() == null ? Owner.FeeType.PERCENT : r.managementFeeType());
        BigDecimal fee = r.managementFeeValue() == null ? BigDecimal.ZERO : r.managementFeeValue();
        if (o.getManagementFeeType() == Owner.FeeType.PERCENT && fee.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw ApiException.badRequest("INVALID_FEE", "A percentage management fee cannot exceed 100");
        }
        o.setManagementFeeValue(fee.setScale(2, RoundingMode.HALF_UP));
        o.setRemittanceDay(r.remittanceDay());
        o.setNotes(r.notes());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
