package com.propertypro.dto;

import com.propertypro.entity.Invoice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class BillingDtos {

    private BillingDtos() {}

    public record InvoiceLineResponse(String chargeTypeCode, String description, BigDecimal quantity,
                                      BigDecimal unitPrice, BigDecimal amount, BigDecimal vatRate, BigDecimal vatAmount) {}

    public record InvoiceResponse(
            UUID id, String invoiceNo, String status, String source,
            UUID tenantId, String tenantName, UUID leaseId, UUID unitId, String unitNo, String paymentCode,
            UUID propertyId, String propertyName,
            LocalDate periodStart, LocalDate periodEnd, LocalDate issueDate, LocalDate dueDate,
            BigDecimal subtotal, BigDecimal vatAmount, BigDecimal total, BigDecimal withholdingTaxAmount,
            BigDecimal amountPaid, BigDecimal balance, boolean overdue, String etimsStatus,
            OffsetDateTime createdAt, List<InvoiceLineResponse> lines) {

        public static InvoiceResponse from(Invoice i, boolean withLines) {
            var u = i.getUnit();
            var p = i.getProperty();
            boolean open = i.getStatus() == Invoice.Status.ISSUED || i.getStatus() == Invoice.Status.PARTIALLY_PAID;
            return new InvoiceResponse(i.getId(), i.getInvoiceNo(), i.getStatus().name(), i.getSource().name(),
                    i.getTenant().getId(), i.getTenant().getName(), i.getLease() == null ? null : i.getLease().getId(),
                    u == null ? null : u.getId(), u == null ? null : u.getUnitNo(), u == null ? null : u.getPaymentCode(),
                    p == null ? null : p.getId(), p == null ? null : p.getName(),
                    i.getPeriodStart(), i.getPeriodEnd(), i.getIssueDate(), i.getDueDate(),
                    i.getSubtotal(), i.getVatAmount(), i.getTotal(), i.getWithholdingTaxAmount(),
                    i.getAmountPaid(), i.getBalance(), open && i.getDueDate().isBefore(LocalDate.now()),
                    i.getEtimsStatus(), i.getCreatedAt(),
                    withLines ? i.getLines().stream().map(l -> new InvoiceLineResponse(l.getChargeType().getCode(),
                            l.getDescription(), l.getQuantity(), l.getUnitPrice(), l.getAmount(), l.getVatRate(),
                            l.getVatAmount())).toList() : List.of());
        }
    }

    public record OneOffLine(
            @NotBlank String chargeTypeCode,
            @NotBlank @Size(max = 255) String description,
            @DecimalMin(value = "0", inclusive = false) BigDecimal quantity,
            @NotNull @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal unitPrice) {}

    public record OneOffInvoiceRequest(
            @NotNull UUID tenantId,
            /** Optional: links the invoice to the tenant's unit and applies the lease's VAT setting. */
            UUID leaseId,
            LocalDate dueDate,
            @NotEmpty @Size(max = 50) List<@Valid OneOffLine> lines,
            String notes) {}

    public record VoidRequest(@NotBlank @Size(max = 500) String reason) {}

    public record WithholdingTaxRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal amount,
            @NotBlank @Size(max = 50) String certificateNo) {}

    public record CreditNoteRequest(
            @NotNull UUID tenantId,
            UUID invoiceId,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank @Size(max = 500) String reason) {}

    public record CreditNoteResponse(UUID id, String creditNoteNo, UUID tenantId, UUID invoiceId,
                                     BigDecimal amount, BigDecimal amountAllocated, String reason, LocalDate issueDate) {}

    public record BillingRunResult(LocalDate runDate, int invoicesCreated, BigDecimal totalBilled,
                                   int leasesSkipped, List<String> problems) {}

    public record StatementLine(LocalDate date, String type, String reference, String description,
                                BigDecimal charged, BigDecimal paid, BigDecimal balance) {}

    public record StatementResponse(UUID tenantId, String tenantName, LocalDate from, LocalDate to,
                                    BigDecimal openingBalance, BigDecimal closingBalance, List<StatementLine> lines) {}

    /** What tenants enter on M-Pesa: the Paybill and the account (e.g. 522522 / 1234567890#KLM-B4). */
    public record PayTo(String paybill, String account) {}

    /** An SMS was queued for the tenant; it goes out within about 30 seconds. */
    public record SmsQueued(String phone, String message) {}
}
