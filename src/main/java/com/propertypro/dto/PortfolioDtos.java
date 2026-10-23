package com.propertypro.dto;

import com.propertypro.dto.PortfolioDtos.BulkUnitsRequest;
import com.propertypro.dto.PortfolioDtos.PropertyDetailResponse;
import com.propertypro.dto.PortfolioDtos.PropertyRequest;
import com.propertypro.dto.PortfolioDtos.PropertyResponse;
import com.propertypro.dto.PortfolioDtos.UnitRequest;
import com.propertypro.dto.PortfolioDtos.UnitResponse;
import com.propertypro.entity.Owner;
import com.propertypro.entity.Property;
import com.propertypro.entity.Unit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies for owners, properties and units. */
public final class PortfolioDtos {

    private PortfolioDtos() {}

    static final String KE_PHONE = "^\\+254[17]\\d{8}$";
    static final String PHONE_MSG = "must be a Kenyan number like +254712345678";
    /** KRA PIN: A or P, nine digits, a letter (e.g. A012345678Z). */
    static final String KRA_PIN = "^[AP]\\d{9}[A-Z]$";

    // ------------------------------------------------------------------ owners
    public record OwnerRequest(
            Owner.Type ownerType,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 30) String idNumber,
            @Pattern(regexp = KRA_PIN, message = "must look like A012345678Z") String kraPin,
            @Email @Size(max = 200) String email,
            @Pattern(regexp = KE_PHONE, message = PHONE_MSG) String phone,
            String address,
            @Size(max = 100) String bankName,
            @Size(max = 100) String bankBranch,
            @Size(max = 150) String bankAccountName,
            @Pattern(regexp = KE_PHONE, message = PHONE_MSG) String mpesaPhone,
            Owner.FeeType managementFeeType,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal managementFeeValue,
            @Min(1) @Max(28) Short remittanceDay,
            String notes) {}

    public record OwnerResponse(
            UUID id, String ownerType, String name, String idNumber, String kraPin, String email, String phone,
            String address, String bankName, String bankBranch, String bankAccountName, String mpesaPhone,
            String managementFeeType, BigDecimal managementFeeValue, Short remittanceDay, String notes,
            OffsetDateTime createdAt) {

        public static OwnerResponse from(Owner o) {
            return new OwnerResponse(o.getId(), o.getOwnerType().name(), o.getName(), o.getIdNumber(), o.getKraPin(),
                    o.getEmail(), o.getPhone(), o.getAddress(), o.getBankName(), o.getBankBranch(),
                    o.getBankAccountName(), o.getMpesaPhone(), o.getManagementFeeType().name(),
                    o.getManagementFeeValue(), o.getRemittanceDay(), o.getNotes(), o.getCreatedAt());
        }
    }

    // -------------------------------------------------------------- properties
    public record PropertyRequest(
            UUID ownerId,
            /** Only used on create; the code cannot change because unit payment codes are built from it. */
            @Pattern(regexp = "^[A-Za-z0-9]{2,10}$", message = "must be 2 to 10 letters or digits") String code,
            @NotBlank @Size(max = 150) String name,
            @NotNull Property.Type propertyType,
            @Size(max = 60) String county,
            @Size(max = 80) String town,
            @Size(max = 100) String estate,
            String address,
            @DecimalMin("-90") @DecimalMax("90") BigDecimal latitude,
            @DecimalMin("-180") @DecimalMax("180") BigDecimal longitude,
            @Size(max = 60) String lrNumber,
            @Min(1900) @Max(2100) Short yearBuilt,
            @Min(0) @Max(200) Short floors,
            String description,
            @Size(max = 50) List<@NotBlank @Size(max = 50) String> amenities,
            Owner.FeeType managementFeeType,
            @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal managementFeeValue) {}

    public record OwnerRef(UUID id, String name) {}

    public record PropertyResponse(
            UUID id, String code, String name, String propertyType, String status, OwnerRef owner,
            String county, String town, String estate, String address, BigDecimal latitude, BigDecimal longitude,
            String lrNumber, Short yearBuilt, Short floors, String description, List<String> amenities,
            String managementFeeType, BigDecimal managementFeeValue, OffsetDateTime createdAt) {

        public static PropertyResponse from(Property p) {
            Owner o = p.getOwner();
            return new PropertyResponse(p.getId(), p.getCode(), p.getName(), p.getPropertyType().name(),
                    p.getStatus().name(), o == null ? null : new OwnerRef(o.getId(), o.getName()),
                    p.getCounty(), p.getTown(), p.getEstate(), p.getAddress(), p.getLatitude(), p.getLongitude(),
                    p.getLrNumber(), p.getYearBuilt(), p.getFloors(), p.getDescription(), p.getAmenities(),
                    p.getManagementFeeType() == null ? null : p.getManagementFeeType().name(),
                    p.getManagementFeeValue(), p.getCreatedAt());
        }
    }

    /** Figures from the v_property_occupancy view. */
    public record PropertyStats(
            long totalUnits, long occupiedUnits, long vacantUnits, BigDecimal occupancyPct,
            BigDecimal potentialMonthlyRent, BigDecimal actualMonthlyRent, BigDecimal vacancyLoss) {

        public static final PropertyStats EMPTY = new PropertyStats(0, 0, 0, null,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public record PropertyDetailResponse(PropertyResponse property, PropertyStats stats) {}

    // ------------------------------------------------------------------- units
    public record UnitRequest(
            @NotBlank @Size(max = 20) String unitNo,
            @Size(max = 30) String block,
            @Size(max = 20) String floor,
            @NotNull Unit.Type unitType,
            @Min(0) @Max(20) Short bedrooms,
            @Min(0) @Max(20) Short bathrooms,
            @DecimalMin(value = "0", inclusive = false) @Digits(integer = 8, fraction = 2) BigDecimal sizeSqft,
            /** Monthly; worked out from rentPerSqft when that is given. */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal marketRent,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal depositAmount,
            /** Monthly; worked out from serviceChargePerSqft when that is given. */
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal serviceCharge,
            /** KES per sq ft per month; needs sizeSqft. */
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal rentPerSqft,
            @DecimalMin("0") @Digits(integer = 8, fraction = 4) BigDecimal serviceChargePerSqft,
            /** Optional; defaults to PROPERTYCODE-UNITNO, e.g. KLM-B4. */
            @Pattern(regexp = "^[A-Za-z0-9-]{2,30}$", message = "must be 2 to 30 letters, digits or dashes") String paymentCode,
            @Size(max = 40) String waterMeterNo,
            @Size(max = 40) String electricityMeterNo,
            String notes) {}

    public record BulkUnitsRequest(@NotEmpty @Size(max = 500) List<@Valid UnitRequest> units) {}

    public record UnitStatusRequest(@NotNull Unit.Status status) {}

    public record UnitResponse(
            UUID id, UUID propertyId, String unitNo, String block, String floor, String unitType,
            Short bedrooms, Short bathrooms, BigDecimal sizeSqft, BigDecimal marketRent, BigDecimal depositAmount,
            BigDecimal serviceCharge, BigDecimal rentPerSqft, BigDecimal serviceChargePerSqft,
            String paymentCode, String waterMeterNo, String electricityMeterNo,
            String status, String notes, OffsetDateTime createdAt) {

        public static UnitResponse from(Unit u) {
            return new UnitResponse(u.getId(), u.getProperty().getId(), u.getUnitNo(), u.getBlock(), u.getFloor(),
                    u.getUnitType().name(), u.getBedrooms(), u.getBathrooms(), u.getSizeSqft(), u.getMarketRent(),
                    u.getDepositAmount(), u.getServiceCharge(), u.getRentPerSqft(), u.getServiceChargePerSqft(),
                    u.getPaymentCode(), u.getWaterMeterNo(),
                    u.getElectricityMeterNo(), u.getStatus().name(), u.getNotes(), u.getCreatedAt());
        }
    }

    public record PropertyStatusRequest(@NotNull Property.Status status) {}
}
