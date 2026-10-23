package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/** A recurring charge on a lease besides rent: service charge, garbage, parking, metered water. */
@Getter
@Setter
@Entity
@Table(name = "lease_charges")
public class LeaseCharge extends TenantRow {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "lease_id", nullable = false, updatable = false)
    private Lease lease;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "charge_type_id", nullable = false)
    private ChargeType chargeType;

    /** Fixed amount per billing period (0 for metered charges). */
    @Column(nullable = false)
    private BigDecimal amount = BigDecimal.ZERO;

    @Column(name = "is_metered", nullable = false)
    private boolean metered;

    /** KES per unit consumed, e.g. per m³ of water. */
    @Column(name = "rate_per_unit")
    private BigDecimal ratePerUnit;

    /** KES per sq ft per month, e.g. commercial service charge; amount = lease area × rate × months. */
    @Column(name = "rate_per_sqft")
    private BigDecimal ratePerSqft;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
