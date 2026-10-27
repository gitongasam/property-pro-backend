package com.propertypro.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies for the platform admin console (/api/v1/admin). */
public final class AdminDtos {

    private AdminDtos() {}

    /** One line in the organisations list. */
    public record OrganizationRow(UUID id, String name, String slug, String email, String phone, String status,
                                  String planCode, String subscriptionStatus, OffsetDateTime trialEndsAt,
                                  LocalDate currentPeriodEnd, long units, long users, OffsetDateTime createdAt) {}

    public record SubscriptionInfo(UUID id, String planCode, String planName, String status, String billingCycle,
                                   BigDecimal price, LocalDate currentPeriodStart, LocalDate currentPeriodEnd,
                                   OffsetDateTime trialEndsAt, OffsetDateTime graceEndsAt, boolean writable) {}

    /** Usage against the plan's limits; a null limit means unlimited. */
    public record Usage(long properties, long units, Integer unitLimit, long tenants, long activeLeases,
                        long staffUsers, Integer userLimit) {}

    public record StaffUser(UUID id, String name, String email, String phone, String status, List<String> roles,
                            OffsetDateTime lastLoginAt) {}

    public record AdminAction(long id, String action, String reason, Map<String, Object> payload,
                              String adminName, OffsetDateTime createdAt) {}

    public record OrganizationDetail(UUID id, String name, String slug, String legalName, String email, String phone,
                                     String county, String town, String status, OffsetDateTime suspendedAt,
                                     String suspendedReason, OffsetDateTime createdAt, OffsetDateTime lastActivityAt,
                                     SubscriptionInfo subscription, Usage usage, List<StaffUser> staff,
                                     List<AdminAction> recentActions) {}

    public record SuspendRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ReasonRequest(@Size(max = 500) String reason) {}

    public record ExtendTrialRequest(@NotNull @Min(1) @Max(90) Integer days, @NotBlank @Size(max = 500) String reason) {}
}
