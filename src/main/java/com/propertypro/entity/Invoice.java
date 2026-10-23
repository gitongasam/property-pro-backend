package com.propertypro.entity;

import com.propertypro.tenancy.TenantContext;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * amount_paid, balance and (once issued) status are kept up to date by the
 * payment_allocations trigger, so they are read-only here and @DynamicUpdate
 * makes Hibernate write only the columns this code changes.
 */
@Getter
@Setter
@Entity
@DynamicUpdate
@Table(name = "invoices")
public class Invoice extends TenantRow {

    public enum Source { SCHEDULED, MANUAL, PENALTY, MOVE_IN, MOVE_OUT, OPENING }

    public enum Status { DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID }

    @Column(name = "invoice_no", nullable = false, updatable = false)
    private String invoiceNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "lease_id", updatable = false)
    private Lease lease;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id", updatable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "property_id", updatable = false)
    private Property property;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Source source = Source.MANUAL;

    @Column(name = "period_start")
    private LocalDate periodStart;

    @Column(name = "period_end")
    private LocalDate periodEnd;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BigDecimal.ZERO;

    @Column(name = "withholding_tax_amount", nullable = false)
    private BigDecimal withholdingTaxAmount = BigDecimal.ZERO;

    @Column(name = "wht_certificate_no")
    private String whtCertificateNo;

    /** Maintained by the allocation trigger. */
    @Column(name = "amount_paid", insertable = false, updatable = false)
    private BigDecimal amountPaid;

    /** Generated column: total - amount_paid - withholding_tax_amount. */
    @Column(insertable = false, updatable = false)
    private BigDecimal balance;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ISSUED;

    @Column(name = "penalty_applied_at")
    private OffsetDateTime penaltyAppliedAt;

    @Column(name = "etims_status")
    private String etimsStatus;

    @Column(name = "etims_invoice_no")
    private String etimsInvoiceNo;

    private String notes;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @Column(name = "voided_at")
    private OffsetDateTime voidedAt;

    @Column(name = "void_reason")
    private String voidReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder")
    private List<InvoiceLine> lines = new ArrayList<>();

    @PrePersist
    void fillCreatedBy() {
        if (createdBy == null) {
            createdBy = TenantContext.userId().orElse(null);
        }
    }

    public void addLine(InvoiceLine line) {
        line.setInvoice(this);
        line.setSortOrder((short) lines.size());
        lines.add(line);
    }

    /** Recomputes subtotal, VAT and total from the lines. */
    public void recalculate() {
        subtotal = lines.stream().map(InvoiceLine::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        vatAmount = lines.stream().map(InvoiceLine::getVatAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        total = subtotal.add(vatAmount);
    }
}
