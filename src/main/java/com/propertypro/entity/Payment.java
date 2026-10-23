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
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Money received. amount_allocated and (after matching) status are kept by the allocation trigger. */
@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "payments")
public class Payment extends TenantRow {

    public enum Method { MPESA, BANK, CHEQUE, CASH, CARD, OTHER }

    public enum Source { MPESA_C2B, MPESA_STK, MANUAL, BANK_IMPORT, BANK_IPN }

    public enum Status { UNMATCHED, UNALLOCATED, PARTIAL, ALLOCATED, REVERSED }

    @Column(name = "receipt_no", nullable = false, updatable = false)
    private String receiptNo;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id")
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id")
    private Property property;

    @Column(name = "payment_channel_id")
    private UUID paymentChannelId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Method method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source = Source.MANUAL;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Column(name = "amount_allocated", insertable = false, updatable = false)
    private BigDecimal amountAllocated;

    @Column(insertable = false, updatable = false)
    private BigDecimal unallocated;

    @Column(name = "payer_name")
    private String payerName;

    @Column(name = "payer_phone")
    private String payerPhone;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private OffsetDateTime paidAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.UNMATCHED;

    @Column(name = "reversed_at")
    private OffsetDateTime reversedAt;

    @Column(name = "reversal_reason")
    private String reversalReason;

    private String notes;

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
