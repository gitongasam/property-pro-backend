package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "invoice_lines")
public class InvoiceLine extends TenantRow {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false, updatable = false)
    private Invoice invoice;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "charge_type_id", nullable = false)
    private ChargeType chargeType;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "meter_reading_id")
    private UUID meterReadingId;

    @Column(name = "sort_order", nullable = false)
    private short sortOrder;
}
