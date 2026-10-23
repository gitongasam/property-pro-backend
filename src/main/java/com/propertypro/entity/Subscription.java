package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
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
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "subscriptions")
public class Subscription {

    public enum Status { TRIALING, ACTIVE, PAST_DUE, CANCELLED, EXPIRED }

    public enum BillingCycle { MONTHLY, ANNUAL }

    /** Statuses that count as the organisation's current subscription. */
    public static final Set<Status> LIVE = EnumSet.of(Status.TRIALING, Status.ACTIVE, Status.PAST_DUE);

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "plan_id")
    private Plan plan;

    @Enumerated(EnumType.STRING)
    private Status status;

    @Enumerated(EnumType.STRING)
    @Column(name = "billing_cycle")
    private BillingCycle billingCycle = BillingCycle.MONTHLY;

    private BigDecimal price;

    @Column(name = "extra_units")
    private int extraUnits;

    @Column(name = "current_period_start")
    private LocalDate currentPeriodStart;

    @Column(name = "current_period_end")
    private LocalDate currentPeriodEnd;

    @Column(name = "trial_ends_at")
    private OffsetDateTime trialEndsAt;

    @Column(name = "grace_ends_at")
    private OffsetDateTime graceEndsAt;

    @Column(name = "cancel_at_period_end")
    private boolean cancelAtPeriodEnd;

    @Column(name = "cancelled_at")
    private OffsetDateTime cancelledAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** A trial past its end date, or a past-due subscription past its grace period, is read-only. */
    public boolean allowsWrites(OffsetDateTime now) {
        return switch (status) {
            case TRIALING -> trialEndsAt == null || now.isBefore(trialEndsAt);
            case ACTIVE -> true;
            case PAST_DUE -> graceEndsAt == null || now.isBefore(graceEndsAt);
            case CANCELLED, EXPIRED -> false;
        };
    }
}
