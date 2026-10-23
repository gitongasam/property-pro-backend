package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Rent, deposit, water, service charge... created per organisation at sign-up. */
@Getter
@Setter
@Entity
@Table(name = "charge_types")
public class ChargeType extends TenantRow {

    public enum Category { RENT, SERVICE, UTILITY, DEPOSIT, PENALTY, OTHER }

    public static final String RENT = "RENT";
    public static final String DEPOSIT = "DEPOSIT";
    public static final String PENALTY = "PENALTY";
    public static final String OPENING_BALANCE = "OPENING_BALANCE";
    public static final String WATER = "WATER";

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "is_taxable", nullable = false)
    private boolean taxable;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate = BigDecimal.ZERO;

    /** false = income belongs to the managing organisation (e.g. agency fee) or is held (deposit). */
    @Column(name = "owner_income", nullable = false)
    private boolean ownerIncome = true;

    @Column(name = "allocation_priority", nullable = false)
    private short allocationPriority = 50;

    @Column(nullable = false)
    private boolean active = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
