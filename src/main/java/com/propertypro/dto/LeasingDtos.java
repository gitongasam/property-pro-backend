package com.propertypro.dto;

import com.propertypro.entity.ChargeType;
import com.propertypro.entity.Lease;
import com.propertypro.entity.Tenant;
import com.propertypro.security.CryptoService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LeasingDtos {

    private LeasingDtos() {}

    static final String KE_PHONE = "^\\+254[17]\\d{8}$";
    static final String PHONE_MSG = "must be a Kenyan number like +254712345678";

    // ----------------------------------------------------------------- tenants
    public record TenantRequest(
            Tenant.Type tenantType,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 150) String contactPerson,
            Tenant.IdType idType,
            @Size(max = 30) String idNumber,
            @Pattern(regexp = "^[AP]\\d{9}[A-Z]$", message = "must look like A012345678Z") String kraPin,
            @Email @Size(max = 200) String email,
            @NotBlank @Pattern(regexp = KE_PHONE, message = PHONE_MSG) String phone,
            @Pattern(regexp = KE_PHONE, message = PHONE_MSG) String altPhone,
            @Size(max = 150) String employer,
            String postalAddress,
            @Size(max = 150) String nextOfKinName,
            @Pattern(regexp = KE_PHONE, message = PHONE_MSG) String nextOfKinPhone,
            @Size(max = 50) String nextOfKinRelationship,
            String notes) {}

    public record TenantResponse(
            UUID id, String tenantType, String name, String contactPerson, String idType, String idNumberMasked,
            String kraPin, String email, String phone, String altPhone, String employer, String postalAddress,
            String nextOfKinName, String nextOfKinPhone, String nextOfKinRelationship, String accountCode,
            String status, String notes, OffsetDateTime createdAt) {

        public static TenantResponse from(Tenant t, CryptoService crypto) {
            return new TenantResponse(t.getId(), t.getTenantType().name(), t.getName(), t.getContactPerson(),
                    t.getIdType() == null ? null : t.getIdType().name(),
                    CryptoService.mask(crypto.decrypt(t.getIdNumberEnc())),
                    t.getKraPin(), t.getEmail(), t.getPhone(), t.getAltPhone(), t.getEmployer(), t.getPostalAddress(),
                    t.getNextOfKinName(), t.getNextOfKinPhone(), t.getNextOfKinRelationship(), t.getAccountCode(),
                    t.getStatus().name(), t.getNotes(), t.getCreatedAt());
        }
    }

    /** Tenant row in lists, with what they owe (from v_tenant_balances). */
    public record TenantListItem(UUID id, String name, String phone, String accountCode, String status,
                                 String currentUnit, BigDecimal balance) {}

    // ------------------------------------------------------------------ leases
    public record LeaseChargeRequest(
            @NotBlank String chargeTypeCode,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            boolean metered,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal ratePerUnit,
            /** KES per sq ft per month, in place of amount; billed on the lease area. */
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal ratePerSqft) {}

    public record LeaseRequest(
            @NotNull UUID unitId,
            @NotNull UUID tenantId,
            Lease.Type leaseType,
            @NotNull LocalDate startDate,
            LocalDate endDate,
            /** Per billing period; worked out from rentPerSqft when that is given (or the unit has one). */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal rentAmount,
            /** Defaults to the unit's size. */
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal areaSqft,
            /** KES per sq ft per month. */
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal rentPerSqft,
            Lease.Frequency billingFrequency,
            @Min(1) @Max(28) Short billingDay,
            @Min(0) @Max(60) Short dueDays,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal depositAmount,
            Lease.Adjust escalationType,
            @DecimalMin("0") BigDecimal escalationValue,
            @Min(1) @Max(120) Short escalationIntervalMonths,
            boolean vatApplicable,
            boolean withholdingTaxApplicable,
            Lease.Adjust penaltyType,
            @DecimalMin("0") BigDecimal penaltyValue,
            @Min(0) @Max(60) Short penaltyGraceDays,
            @Min(0) @Max(365) Short noticePeriodDays,
            @Size(max = 20) List<@Valid LeaseChargeRequest> charges,
            String notes) {}

    public record ActivateRequest(
            LocalDate moveInDate,
            /** Issue the move-in invoice (deposit + first period) now. Default true. */
            Boolean issueMoveInInvoice) {}

    public record NoticeRequest(@NotNull LocalDate noticeDate, LocalDate moveOutDate) {}

    public record TerminateRequest(@NotNull LocalDate moveOutDate, @Size(max = 500) String reason) {}

    public record LeaseChargeResponse(UUID id, String chargeTypeCode, String chargeTypeName, BigDecimal amount,
                                      boolean metered, BigDecimal ratePerUnit, BigDecimal ratePerSqft) {}

    public record LeaseResponse(
            UUID id, String leaseNo, String status, String leaseType,
            UUID unitId, String unitNo, String paymentCode, UUID propertyId, String propertyName,
            UUID tenantId, String tenantName,
            LocalDate startDate, LocalDate endDate, BigDecimal rentAmount, BigDecimal areaSqft, BigDecimal rentPerSqft,
            String billingFrequency,
            short billingDay, short dueDays, LocalDate nextBillingDate, BigDecimal depositAmount,
            String escalationType, BigDecimal escalationValue, Short escalationIntervalMonths, LocalDate nextEscalationDate,
            boolean vatApplicable, boolean withholdingTaxApplicable,
            String penaltyType, BigDecimal penaltyValue, short penaltyGraceDays,
            short noticePeriodDays, LocalDate noticeGivenDate, LocalDate moveInDate, LocalDate moveOutDate,
            List<LeaseChargeResponse> charges, String notes) {

        public static LeaseResponse from(Lease l) {
            var u = l.getUnit();
            var p = u.getProperty();
            return new LeaseResponse(l.getId(), l.getLeaseNo(), l.getStatus().name(), l.getLeaseType().name(),
                    u.getId(), u.getUnitNo(), u.getPaymentCode(), p.getId(), p.getName(),
                    l.getTenant().getId(), l.getTenant().getName(),
                    l.getStartDate(), l.getEndDate(), l.getRentAmount(), l.getAreaSqft(), l.getRentPerSqft(),
                    l.getBillingFrequency().name(),
                    l.getBillingDay(), l.getDueDays(), l.getNextBillingDate(), l.getDepositAmount(),
                    l.getEscalationType().name(), l.getEscalationValue(), l.getEscalationIntervalMonths(),
                    l.getNextEscalationDate(), l.isVatApplicable(), l.isWithholdingTaxApplicable(),
                    l.getPenaltyType().name(), l.getPenaltyValue(), l.getPenaltyGraceDays(),
                    l.getNoticePeriodDays(), l.getNoticeGivenDate(), l.getMoveInDate(), l.getMoveOutDate(),
                    l.getCharges().stream().map(c -> new LeaseChargeResponse(c.getId(), c.getChargeType().getCode(),
                            c.getChargeType().getName(), c.getAmount(), c.isMetered(), c.getRatePerUnit(),
                            c.getRatePerSqft())).toList(),
                    l.getNotes());
        }
    }

    public record ChargeTypeResponse(UUID id, String code, String name, String category, boolean taxable,
                                     BigDecimal vatRate, boolean ownerIncome, short allocationPriority) {
        public static ChargeTypeResponse from(ChargeType c) {
            return new ChargeTypeResponse(c.getId(), c.getCode(), c.getName(), c.getCategory().name(), c.isTaxable(),
                    c.getVatRate(), c.isOwnerIncome(), c.getAllocationPriority());
        }
    }
}
