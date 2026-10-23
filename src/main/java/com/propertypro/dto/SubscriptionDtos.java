package com.propertypro.dto;

import com.propertypro.entity.Subscription;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Settings → Billing: the organisation's own subscription and paying for it. */
public final class SubscriptionDtos {

    private SubscriptionDtos() {}

    public record SubscriptionView(String planCode, String planName, String status, String billingCycle,
                                   BigDecimal price, String currency, LocalDate currentPeriodStart,
                                   LocalDate currentPeriodEnd, OffsetDateTime trialEndsAt, boolean writable) {}

    public record UpgradeRequest(
            @NotBlank String planCode,
            @NotNull Subscription.BillingCycle billingCycle,
            @NotBlank @Pattern(regexp = "^\\+254[17]\\d{8}$", message = "must be a Kenyan number like +254712345678") String phone) {}

    /** The prompt is on its way; poll the payment until it is no longer PENDING. */
    public record UpgradeResponse(UUID paymentId, String checkoutRequestId, BigDecimal amount, String message) {}

    public record PaymentView(UUID id, String status, String planCode, String billingCycle, BigDecimal amount,
                              String phone, String reference, Integer resultCode, String resultDesc,
                              OffsetDateTime paidAt, OffsetDateTime createdAt) {}
}
