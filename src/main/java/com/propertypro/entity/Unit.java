package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

@Getter
@Setter
@Entity
@Table(name = "units")
@SQLRestriction("deleted_at IS NULL")
public class Unit extends TenantEntity {

    public enum Type {
        BEDSITTER, STUDIO, ONE_BR, TWO_BR, THREE_BR, FOUR_PLUS_BR, MAISONETTE, HOUSE,
        SHOP, OFFICE, WAREHOUSE, STALL, PARKING, OTHER
    }

    public enum Status { VACANT, OCCUPIED, RESERVED, UNDER_MAINTENANCE, UNAVAILABLE }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false, updatable = false)
    private Property property;

    @Column(name = "unit_no", nullable = false)
    private String unitNo;

    private String block;
    private String floor;

    @Enumerated(EnumType.STRING)
    @Column(name = "unit_type", nullable = false)
    private Type unitType;

    private Short bedrooms;
    private Short bathrooms;

    @Column(name = "size_sqft")
    private BigDecimal sizeSqft;

    @Column(name = "market_rent", nullable = false)
    private BigDecimal marketRent = BigDecimal.ZERO;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(name = "service_charge", nullable = false)
    private BigDecimal serviceCharge = BigDecimal.ZERO;

    /** KES per sq ft per month for space let by area; when set, marketRent = sizeSqft × rentPerSqft. */
    @Column(name = "rent_per_sqft")
    private BigDecimal rentPerSqft;

    /** When set, serviceCharge = sizeSqft × serviceChargePerSqft. */
    @Column(name = "service_charge_per_sqft")
    private BigDecimal serviceChargePerSqft;

    /** The account number tenants type on the Paybill. Stays with the unit across tenants. */
    @Column(name = "payment_code", nullable = false)
    private String paymentCode;

    @Column(name = "water_meter_no")
    private String waterMeterNo;

    @Column(name = "electricity_meter_no")
    private String electricityMeterNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.VACANT;

    private String notes;
}
