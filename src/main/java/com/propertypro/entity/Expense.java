package com.propertypro.entity;

import com.propertypro.tenancy.TenantContext;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "expenses")
public class Expense extends TenantRow {

    public enum Category { REPAIRS, UTILITIES, SECURITY, CLEANING, GARDENING, INSURANCE, LAND_RATES, LEGAL,
        SALARIES, COMMISSION, OFFICE, OTHER }

    public enum ChargeTo { OWNER, TENANT, ORGANIZATION }

    public enum Status { PENDING_APPROVAL, APPROVED, PAID, REJECTED }

    @Column(name = "expense_no", nullable = false, updatable = false)
    private String expenseNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private Property property;

    @Column(name = "unit_id")
    private UUID unitId;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "maintenance_request_id")
    private UUID maintenanceRequestId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal amount;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "expense_date", nullable = false)
    private LocalDate expenseDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_to", nullable = false)
    private ChargeTo chargeTo = ChargeTo.OWNER;

    @Column(name = "payment_method")
    private String paymentMethod;

    private String reference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PENDING_APPROVAL;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "owner_statement_id")
    private UUID ownerStatementId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @PrePersist
    void fillCreatedBy() {
        if (createdBy == null) {
            createdBy = TenantContext.userId().orElse(null);
        }
    }
}
