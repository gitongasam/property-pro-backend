package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.common.Area;
import com.propertypro.common.DocumentNumbers;
import com.propertypro.common.Money;
import com.propertypro.dto.BillingDtos.PayTo;
import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.LeasingDtos.ActivateRequest;
import com.propertypro.dto.LeasingDtos.LeaseChargeRequest;
import com.propertypro.dto.LeasingDtos.LeaseRequest;
import com.propertypro.dto.LeasingDtos.LeaseResponse;
import com.propertypro.dto.LeasingDtos.NoticeRequest;
import com.propertypro.dto.LeasingDtos.TerminateRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.ChargeType;
import com.propertypro.entity.Lease;
import com.propertypro.entity.LeaseCharge;
import com.propertypro.entity.Tenant;
import com.propertypro.entity.Unit;
import com.propertypro.repository.ChargeTypeRepository;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.repository.UnitRepository;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LeaseServiceImpl implements LeaseService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final LeaseRepository leases;
    private final TenantService tenantService;
    private final UnitRepository units;
    private final ChargeTypeRepository chargeTypes;
    private final DocumentNumbers numbers;
    private final BillingService billing;
    private final PlanGate planGate;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    @Override
    @Transactional(readOnly = true)
    public PageResponse<LeaseResponse> list(Lease.Status status, UUID propertyId, UUID tenantId,
                                            Integer endingWithinDays, Pageable pageable) {
        Specification<Lease> byStatus = (r, q, cb) -> cb.equal(r.get("status"), status);
        Specification<Lease> byProperty = (r, q, cb) -> cb.equal(r.get("unit").get("property").get("id"), propertyId);
        Specification<Lease> byTenant = (r, q, cb) -> cb.equal(r.get("tenant").get("id"), tenantId);
        Specification<Lease> ending = (r, q, cb) -> cb.and(
                r.get("status").in(Lease.LIVE),
                cb.isNotNull(r.get("endDate")),
                cb.lessThanOrEqualTo(r.<LocalDate>get("endDate"), LocalDate.now().plusDays(endingWithinDays == null ? 0 : endingWithinDays)));
        Specification<Lease> spec = Specification.allOf(
                status == null ? null : byStatus,
                propertyId == null ? null : byProperty,
                tenantId == null ? null : byTenant,
                endingWithinDays == null ? null : ending);
        return PageResponse.of(leases.findAll(spec, pageable), LeaseResponse::from);
    }

    @Override
    @Transactional(readOnly = true)
    public LeaseResponse get(UUID id) {
        return LeaseResponse.from(find(id));
    }

    @Override
    @Transactional
    public LeaseResponse create(LeaseRequest req) {
        planGate.requireWritable();
        Unit unit = units.findById(req.unitId()).orElseThrow(() -> ApiException.notFound("Unit"));
        Tenant tenant = tenantService.find(req.tenantId());
        validateDates(req);

        Lease lease = new Lease();
        lease.setLeaseNo(numbers.next(DocumentNumbers.Type.LEASE));
        lease.setUnit(unit);
        lease.setTenant(tenant);
        apply(lease, req);
        return LeaseResponse.from(leases.save(lease));
    }

    /** Only a DRAFT lease can be edited; an active one is changed by ending it and starting another. */
    @Override
    @Transactional
    public LeaseResponse update(UUID id, LeaseRequest req) {
        planGate.requireWritable();
        Lease lease = find(id);
        if (lease.getStatus() != Lease.Status.DRAFT) {
            throw ApiException.conflict("LEASE_NOT_DRAFT", "Only a draft lease can be edited");
        }
        if (!lease.getUnit().getId().equals(req.unitId()) || !lease.getTenant().getId().equals(req.tenantId())) {
            throw ApiException.badRequest("LEASE_PARTIES_FIXED", "Unit and tenant cannot change; create a new lease");
        }
        validateDates(req);
        lease.getCharges().clear();
        apply(lease, req);
        return LeaseResponse.from(lease);
    }

    @Override
    @Transactional
    public LeaseResponse activate(UUID id, ActivateRequest req) {
        planGate.requireWritable();
        Lease lease = find(id);
        if (lease.getStatus() != Lease.Status.DRAFT) {
            throw ApiException.conflict("LEASE_NOT_DRAFT", "This lease is already " + lease.getStatus().name().toLowerCase());
        }
        Unit unit = lease.getUnit();
        if (unit.getStatus() == Unit.Status.OCCUPIED && !lease.getStartDate().isAfter(LocalDate.now())) {
            throw ApiException.conflict("UNIT_OCCUPIED", "Unit " + unit.getUnitNo() + " already has a tenant");
        }
        if (unit.getStatus() == Unit.Status.UNAVAILABLE) {
            throw ApiException.conflict("UNIT_UNAVAILABLE", "Unit " + unit.getUnitNo() + " is marked unavailable");
        }

        LocalDate start = lease.getStartDate();
        lease.setStatus(Lease.Status.ACTIVE);
        lease.setMoveInDate(req == null || req.moveInDate() == null ? start : req.moveInDate());
        lease.setNextBillingDate(firstRegularBillingDate(lease));
        if (lease.getEscalationType() != Lease.Adjust.NONE && lease.getEscalationIntervalMonths() != null) {
            lease.setNextEscalationDate(start.plusMonths(lease.getEscalationIntervalMonths()));
        }
        unit.setStatus(Unit.Status.OCCUPIED);
        lease.getTenant().setStatus(Tenant.Status.ACTIVE);
        leases.saveAndFlush(lease);   // the exclusion constraint checks for overlapping leases here

        boolean issue = req == null || req.issueMoveInInvoice() == null || req.issueMoveInInvoice();
        if (issue) {
            LocalDate periodEnd = lease.getNextBillingDate().minusDays(1);
            billing.createMoveInInvoice(lease, start, periodEnd, firstPeriodFactor(lease, start, periodEnd));
        }
        notifyTenant(lease);
        return LeaseResponse.from(lease);
    }

    @Override
    @Transactional
    public SmsQueued sendToTenant(UUID id) {
        Lease lease = find(id);
        if (!lease.isLive()) {
            throw ApiException.conflict("LEASE_NOT_ACTIVE", "Only an active lease can be sent to the tenant");
        }
        if (lease.getTenant().getPhone() == null) {
            throw ApiException.badRequest("TENANT_NO_PHONE", lease.getTenant().getName() + " has no phone number");
        }
        notifyTenant(lease);
        return new SmsQueued(lease.getTenant().getPhone(), "Lease " + lease.getLeaseNo() + " is on its way by SMS");
    }

    /** Queues the LEASE_ACTIVATED SMS: unit, dates, rent, deposit and where to pay. */
    private void notifyTenant(Lease lease) {
        Tenant t = lease.getTenant();
        Unit unit = lease.getUnit();
        PayTo payTo = billing.payTo(lease);
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("tenant_name", t.getName());
        vars.put("lease_no", lease.getLeaseNo());
        vars.put("unit", unit.getUnitNo());
        vars.put("property", unit.getProperty().getName());
        vars.put("start_date", lease.getStartDate().format(DAY));
        vars.put("end_date", lease.getEndDate() == null ? "" : " to " + lease.getEndDate().format(DAY));
        vars.put("rent", lease.getRentAmount().toPlainString());
        vars.put("frequency", switch (lease.getBillingFrequency()) {
            case MONTHLY -> "per month";
            case QUARTERLY -> "per quarter";
            case SEMI_ANNUAL -> "every 6 months";
            case ANNUAL -> "per year";
        });
        vars.put("billing_day", lease.getBillingDay());
        vars.put("due_days", lease.getDueDays());
        vars.put("deposit", lease.getDepositAmount().toPlainString());
        vars.put("paybill", payTo.paybill());
        vars.put("payment_code", payTo.account());
        vars.put("org_name", jdbc.sql("SELECT name FROM organizations WHERE id = ?")
                .param(lease.getOrganizationId()).query(String.class).optional().orElse(""));
        notifications.queue(Channel.SMS, Recipient.TENANT, t.getId(), t.getPhone(), "LEASE_ACTIVATED", vars);
    }

    /**
     * For tenants already living in a unit when the organisation moves onto
     * PropertyPro (imports): the lease goes straight to ACTIVE with no move-in
     * invoice, and regular billing starts on the first billing day after
     * {@code today}. Whatever they already owe is entered as an opening balance.
     */
    @Override
    @Transactional
    public Lease importActive(LeaseRequest req, LocalDate today) {
        planGate.requireWritable();
        Unit unit = units.findById(req.unitId()).orElseThrow(() -> ApiException.notFound("Unit"));
        Tenant tenant = tenantService.find(req.tenantId());
        validateDates(req);
        if (unit.getStatus() == Unit.Status.OCCUPIED) {
            throw ApiException.conflict("UNIT_OCCUPIED", "Unit " + unit.getUnitNo() + " already has a tenant");
        }

        Lease lease = new Lease();
        lease.setLeaseNo(numbers.next(DocumentNumbers.Type.LEASE));
        lease.setUnit(unit);
        lease.setTenant(tenant);
        apply(lease, req);
        lease.setStatus(Lease.Status.ACTIVE);
        lease.setMoveInDate(lease.getStartDate());

        LocalDate next = today.withDayOfMonth(Math.min(lease.getBillingDay(), today.lengthOfMonth()));
        if (!next.isAfter(today)) {
            LocalDate m = today.plusMonths(1);
            next = m.withDayOfMonth(Math.min(lease.getBillingDay(), m.lengthOfMonth()));
        }
        lease.setNextBillingDate(next);
        if (lease.getEscalationType() != Lease.Adjust.NONE && lease.getEscalationIntervalMonths() != null) {
            LocalDate esc = lease.getStartDate().plusMonths(lease.getEscalationIntervalMonths());
            while (!esc.isAfter(today)) {
                esc = esc.plusMonths(lease.getEscalationIntervalMonths());
            }
            lease.setNextEscalationDate(esc);
        }
        unit.setStatus(Unit.Status.OCCUPIED);
        tenant.setStatus(Tenant.Status.ACTIVE);
        return leases.saveAndFlush(lease);
    }

    @Override
    @Transactional
    public LeaseResponse giveNotice(UUID id, NoticeRequest req) {
        planGate.requireWritable();
        Lease lease = find(id);
        if (lease.getStatus() != Lease.Status.ACTIVE) {
            throw ApiException.conflict("LEASE_NOT_ACTIVE", "Notice can only be given on an active lease");
        }
        lease.setStatus(Lease.Status.NOTICE_GIVEN);
        lease.setNoticeGivenDate(req.noticeDate());
        LocalDate moveOut = req.moveOutDate() != null ? req.moveOutDate() : req.noticeDate().plusDays(lease.getNoticePeriodDays());
        if (moveOut.isBefore(lease.getStartDate())) {
            throw ApiException.badRequest("INVALID_DATE", "Move-out cannot be before the lease starts");
        }
        lease.setEndDate(moveOut);
        return LeaseResponse.from(lease);
    }

    @Override
    @Transactional
    public LeaseResponse terminate(UUID id, TerminateRequest req) {
        planGate.requireWritable();
        Lease lease = find(id);
        if (!lease.isLive() && lease.getStatus() != Lease.Status.DRAFT) {
            throw ApiException.conflict("LEASE_ENDED", "This lease has already ended");
        }
        if (req.moveOutDate().isBefore(lease.getStartDate())) {
            throw ApiException.badRequest("INVALID_DATE", "Move-out cannot be before the lease starts");
        }
        boolean wasLive = lease.isLive();
        lease.setStatus(Lease.Status.TERMINATED);
        lease.setMoveOutDate(req.moveOutDate());
        lease.setEndDate(req.moveOutDate());
        lease.setTerminationReason(req.reason());
        lease.setNextBillingDate(null);

        if (wasLive) {
            leases.flush();
            Unit unit = lease.getUnit();
            if (leases.findCurrentForUnit(unit.getId(), Lease.LIVE, LocalDate.now()).isEmpty()) {
                unit.setStatus(Unit.Status.VACANT);
            }
            Tenant tenant = lease.getTenant();
            if (leases.findByTenantAndStatus(tenant.getId(), Lease.LIVE).isEmpty()) {
                tenant.setStatus(Tenant.Status.FORMER);
            }
        }
        return LeaseResponse.from(lease);
    }

    @Override
    public Lease find(UUID id) {
        return leases.findWithDetails(id).orElseThrow(() -> ApiException.notFound("Lease"));
    }

    // ------------------------------------------------------------------ helpers

    private void apply(Lease l, LeaseRequest r) {
        l.setLeaseType(r.leaseType() == null ? Lease.Type.RESIDENTIAL : r.leaseType());
        l.setStartDate(r.startDate());
        l.setEndDate(r.endDate());
        l.setBillingFrequency(r.billingFrequency() == null ? Lease.Frequency.MONTHLY : r.billingFrequency());
        int months = l.getBillingFrequency().months;
        Unit unit = l.getUnit();
        l.setAreaSqft(r.areaSqft() != null ? r.areaSqft() : unit.getSizeSqft());
        // A rate on the lease wins; with no rent given at all, fall back to the unit's rate
        BigDecimal rentRate = r.rentPerSqft() != null ? r.rentPerSqft()
                : r.rentAmount() == null ? unit.getRentPerSqft() : null;
        if (rentRate != null) {
            requireArea(l);
            l.setRentPerSqft(rentRate);
            l.setRentAmount(Area.amount(l.getAreaSqft(), rentRate, months));
        } else if (r.rentAmount() != null) {
            l.setRentPerSqft(null);
            l.setRentAmount(Money.of(r.rentAmount()));
        } else {
            throw ApiException.badRequest("RENT_REQUIRED", "Give the rent amount or a rent per sq ft");
        }
        l.setBillingDay(r.billingDay() == null ? 1 : r.billingDay());
        l.setDueDays(r.dueDays() == null ? 5 : r.dueDays());
        l.setDepositAmount(Money.of(r.depositAmount()));
        l.setEscalationType(r.escalationType() == null ? Lease.Adjust.NONE : r.escalationType());
        l.setEscalationValue(r.escalationValue() == null ? BigDecimal.ZERO : r.escalationValue());
        l.setEscalationIntervalMonths(l.getEscalationType() == Lease.Adjust.NONE ? null
                : (r.escalationIntervalMonths() == null ? 12 : r.escalationIntervalMonths()));
        l.setVatApplicable(r.vatApplicable());
        l.setWithholdingTaxApplicable(r.withholdingTaxApplicable());
        l.setPenaltyType(r.penaltyType() == null ? Lease.Adjust.NONE : r.penaltyType());
        l.setPenaltyValue(r.penaltyValue() == null ? BigDecimal.ZERO : r.penaltyValue());
        l.setPenaltyGraceDays(r.penaltyGraceDays() == null ? 0 : r.penaltyGraceDays());
        l.setNoticePeriodDays(r.noticePeriodDays() == null ? 30 : r.noticePeriodDays());
        l.setNotes(r.notes());

        if (r.charges() != null) {
            for (LeaseChargeRequest c : r.charges()) {
                ChargeType type = chargeTypes.findByCode(c.chargeTypeCode().toUpperCase())
                        .orElseThrow(() -> ApiException.badRequest("UNKNOWN_CHARGE", "Unknown charge type " + c.chargeTypeCode()));
                if (ChargeType.RENT.equals(type.getCode()) || ChargeType.DEPOSIT.equals(type.getCode())) {
                    throw ApiException.badRequest("CHARGE_NOT_ALLOWED", "Rent and deposit are set on the lease itself");
                }
                if (c.metered() && c.ratePerUnit() == null) {
                    throw ApiException.badRequest("RATE_REQUIRED", type.getName() + " is metered: give a rate per unit");
                }
                if (c.metered() && c.ratePerSqft() != null) {
                    throw ApiException.badRequest("SQFT_NOT_METERED", type.getName() + " cannot be both metered and per sq ft");
                }
                LeaseCharge charge = new LeaseCharge();
                charge.setChargeType(type);
                if (c.metered()) {
                    charge.setAmount(BigDecimal.ZERO);
                } else if (c.ratePerSqft() != null) {
                    requireArea(l);
                    charge.setRatePerSqft(c.ratePerSqft());
                    charge.setAmount(Area.amount(l.getAreaSqft(), c.ratePerSqft(), months));
                } else {
                    charge.setAmount(Money.of(c.amount()));
                }
                charge.setMetered(c.metered());
                charge.setRatePerUnit(c.ratePerUnit());
                charge.setStartDate(r.startDate());
                charge.setEndDate(r.endDate());
                l.addCharge(charge);
            }
        }
    }

    private static void requireArea(Lease l) {
        if (l.getAreaSqft() == null) {
            throw ApiException.badRequest("AREA_REQUIRED",
                    "Give the area in sq ft (or the size of unit " + l.getUnit().getUnitNo() + ") to charge per sq ft");
        }
    }

    private static void validateDates(LeaseRequest r) {
        if (r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            throw ApiException.badRequest("INVALID_DATE", "The end date is before the start date");
        }
    }

    /**
     * The first regular invoice date after move-in: the next billing day on or
     * after start + one day. A lease starting on its billing day is billed again
     * one full period later.
     */
    static LocalDate firstRegularBillingDate(Lease l) {
        LocalDate start = l.getStartDate();
        int months = l.getBillingFrequency().months;
        if (start.getDayOfMonth() == l.getBillingDay()) {
            return start.plusMonths(months);
        }
        LocalDate candidate = start.withDayOfMonth(Math.min(l.getBillingDay(), start.lengthOfMonth()));
        if (!candidate.isAfter(start)) {
            candidate = candidate.plusMonths(1);
        }
        return candidate.plusMonths(months - 1L);
    }

    /** Monthly leases starting mid-month pay a share of the first month; other frequencies pay in full. */
    static BigDecimal firstPeriodFactor(Lease l, LocalDate from, LocalDate to) {
        if (l.getBillingFrequency() != Lease.Frequency.MONTHLY || from.getDayOfMonth() == l.getBillingDay()) {
            return BigDecimal.ONE;
        }
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        long fullPeriod = ChronoUnit.DAYS.between(to.plusDays(1).minusMonths(1), to.plusDays(1));
        if (days >= fullPeriod) {
            return BigDecimal.ONE;
        }
        return BigDecimal.valueOf(days).divide(BigDecimal.valueOf(fullPeriod), 6, RoundingMode.HALF_UP);
    }
}
