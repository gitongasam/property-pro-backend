package com.propertypro.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Getter
@Setter
@Entity
@Table(name = "leases")
@SQLRestriction("deleted_at IS NULL")
public class Lease extends TenantEntity {

    public enum Type { RESIDENTIAL, COMMERCIAL }

    public enum Status { DRAFT, ACTIVE, NOTICE_GIVEN, EXPIRED, TERMINATED }

    public enum Frequency {
        MONTHLY(1), QUARTERLY(3), SEMI_ANNUAL(6), ANNUAL(12);
        public final int months;
        Frequency(int months) { this.months = months; }
    }

    public enum Adjust { NONE, PERCENT, FIXED }

    /** Statuses in which the tenant is in the unit and being billed. */
    public static final Set<Status> LIVE = EnumSet.of(Status.ACTIVE, Status.NOTICE_GIVEN);

    @Column(name = "lease_no", nullable = false, updatable = false)
    private String leaseNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_id", nullable = false, updatable = false)
    private Unit unit;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false, updatable = false)
    private Tenant tenant;

    @Enumerated(EnumType.STRING)
    @Column(name = "lease_type", nullable = false)
    private Type leaseType = Type.RESIDENTIAL;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "rent_amount", nullable = false)
    private BigDecimal rentAmount;

    /** The area the tenant signed for, copied from the unit unless given. */
    @Column(name = "area_sqft")
    private BigDecimal areaSqft;

    /** When set, rentAmount = areaSqft × rentPerSqft × months in the billing period. */
    @Column(name = "rent_per_sqft")
    private BigDecimal rentPerSqft;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_frequency", nullable = false)
    private Frequency billingFrequency = Frequency.MONTHLY;

    @Column(name = "billing_day", nullable = false)
    private short billingDay = 1;

    @Column(name = "due_days", nullable = false)
    private short dueDays = 5;

    @Column(name = "next_billing_date")
    private LocalDate nextBillingDate;

    @Column(name = "deposit_amount", nullable = false)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "escalation_type", nullable = false)
    private Adjust escalationType = Adjust.NONE;

    @Column(name = "escalation_value", nullable = false)
    private BigDecimal escalationValue = BigDecimal.ZERO;

    @Column(name = "escalation_interval_months")
    private Short escalationIntervalMonths;

    @Column(name = "next_escalation_date")
    private LocalDate nextEscalationDate;

    @Column(name = "vat_applicable", nullable = false)
    private boolean vatApplicable;

    @Column(name = "withholding_tax_applicable", nullable = false)
    private boolean withholdingTaxApplicable;

    @Enumerated(EnumType.STRING)
    @Column(name = "penalty_type", nullable = false)
    private Adjust penaltyType = Adjust.NONE;

    @Column(name = "penalty_value", nullable = false)
    private BigDecimal penaltyValue = BigDecimal.ZERO;

    @Column(name = "penalty_grace_days", nullable = false)
    private short penaltyGraceDays;

    @Column(name = "notice_period_days", nullable = false)
    private short noticePeriodDays = 30;

    @Column(name = "notice_given_date")
    private LocalDate noticeGivenDate;

    @Column(name = "move_in_date")
    private LocalDate moveInDate;

    @Column(name = "move_out_date")
    private LocalDate moveOutDate;

    @Column(name = "termination_reason")
    private String terminationReason;

    private String notes;

    @OneToMany(mappedBy = "lease", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<LeaseCharge> charges = new ArrayList<>();

    public boolean isLive() {
        return LIVE.contains(status);
    }

    public void addCharge(LeaseCharge charge) {
        charge.setLease(this);
        charges.add(charge);
    }
}
