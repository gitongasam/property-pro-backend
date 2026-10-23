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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Getter
@Setter
@Entity
@Table(name = "maintenance_requests")
public class MaintenanceRequest extends TenantRow {

    public enum Category { PLUMBING, ELECTRICAL, CARPENTRY, PAINTING, ROOFING, PEST_CONTROL, LIFT, GENERATOR,
        SECURITY, CLEANING, APPLIANCE, OTHER }

    public enum Priority { LOW, MEDIUM, HIGH, URGENT }

    public enum Status { OPEN, ASSIGNED, IN_PROGRESS, ON_HOLD, RESOLVED, CLOSED, CANCELLED }

    /** Allowed moves on the board. */
    public static final Map<Status, Set<Status>> NEXT = Map.of(
            Status.OPEN, EnumSet.of(Status.ASSIGNED, Status.IN_PROGRESS, Status.CANCELLED),
            Status.ASSIGNED, EnumSet.of(Status.IN_PROGRESS, Status.ON_HOLD, Status.OPEN, Status.CANCELLED),
            Status.IN_PROGRESS, EnumSet.of(Status.ON_HOLD, Status.RESOLVED, Status.CANCELLED),
            Status.ON_HOLD, EnumSet.of(Status.IN_PROGRESS, Status.CANCELLED),
            Status.RESOLVED, EnumSet.of(Status.CLOSED, Status.IN_PROGRESS),
            Status.CLOSED, EnumSet.noneOf(Status.class),
            Status.CANCELLED, EnumSet.noneOf(Status.class));

    @Column(name = "ticket_no", nullable = false, updatable = false)
    private String ticketNo;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "property_id", nullable = false, updatable = false)
    private Property property;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "unit_id")
    private Unit unit;

    @Column(name = "tenant_id")
    private UUID tenantId;

    @Column(name = "raised_by", updatable = false)
    private UUID raisedBy;

    @Column(nullable = false)
    private String title;

    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category = Category.OTHER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.OPEN;

    @Column(name = "assigned_to")
    private UUID assignedTo;

    @Column(name = "vendor_id")
    private UUID vendorId;

    @Column(name = "scheduled_date")
    private LocalDate scheduledDate;

    @Column(name = "resolved_at")
    private OffsetDateTime resolvedAt;

    @Column(name = "estimated_cost")
    private BigDecimal estimatedCost;

    @Column(name = "actual_cost")
    private BigDecimal actualCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "charge_to", nullable = false)
    private Expense.ChargeTo chargeTo = Expense.ChargeTo.OWNER;

    @Column(name = "tenant_rating")
    private Short tenantRating;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;
}
