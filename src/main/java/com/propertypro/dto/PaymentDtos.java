package com.propertypro.dto;

import com.propertypro.entity.Payment;
import com.propertypro.entity.PaymentChannel;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PaymentDtos {

    private PaymentDtos() {}

    public record ManualPaymentRequest(
            @NotNull UUID tenantId,
            @NotNull @DecimalMin(value = "1") @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotNull Payment.Method method,
            @Size(max = 60) String reference,
            OffsetDateTime paidAt,
            @Size(max = 150) String payerName,
            String notes) {}

    public record MatchRequest(@NotNull UUID tenantId) {}

    public record ReverseRequest(@NotBlank @Size(max = 500) String reason) {}

    public record StkPushRequest(
            @NotNull UUID tenantId,
            @NotNull @DecimalMin("1") @Digits(integer = 9, fraction = 0) BigDecimal amount,
            /** Defaults to the tenant's phone. */
            @Pattern(regexp = "^\\+254[17]\\d{8}$", message = "must be a Kenyan number like +254712345678") String phone) {}

    public record StkPushResponse(String checkoutRequestId, String message) {}

    public record PaymentResponse(
            UUID id, String receiptNo, String method, String source, String reference, BigDecimal amount,
            BigDecimal amountAllocated, BigDecimal unallocated, String status,
            UUID tenantId, String tenantName, UUID unitId, String unitNo, String propertyName,
            String payerName, String payerPhone, String accountTyped, OffsetDateTime paidAt, String notes) {}

    public record Suggestion(UUID tenantId, String tenantName, String unit, BigDecimal owes, String reason, int score) {}

    // ------------------------------------------------------------- channels
    public record ChannelRequest(
            @NotNull PaymentChannel.Type channelType,
            @NotBlank @Size(max = 100) String name,
            @Pattern(regexp = "^\\d{5,7}$", message = "must be a 5 to 7 digit Paybill or Till number") String shortcode,
            UUID propertyId,
            PaymentChannel.Environment environment,
            /** Leave blank to keep the stored value. */
            String consumerKey,
            String consumerSecret,
            String passkey,
            @Size(max = 100) String bankName,
            @Pattern(regexp = "^\\d{6,20}$", message = "must be the bank account number, digits only") String bankAccountNo,
            /** BANK_PAYBILL / BANK_ACCOUNT: the bank's code from GET /api/v1/banks (KCB, EQUITY, ..., OTHER). */
            @Size(max = 20) String bankCode) {}

    public record ChannelResponse(
            UUID id, String channelType, String name, String shortcode, UUID propertyId, String environment,
            boolean hasConsumerKey, boolean hasConsumerSecret, boolean hasPasskey, String bankName,
            String bankAccountMasked, String validationUrl, String confirmationUrl, String stkCallbackUrl,
            OffsetDateTime urlsRegisteredAt, OffsetDateTime verifiedAt, boolean active, String testAccount,
            /** Bank channels: the bank, and the URL to give it for payment notifications (banks with a connector). */
            String bankCode, String bankNotificationUrl) {}

    public record ImportSummary(int created, int skipped, List<String> problems) {}

    public record ChannelRef(UUID id, UUID organizationId, String shortcode) {}
}
