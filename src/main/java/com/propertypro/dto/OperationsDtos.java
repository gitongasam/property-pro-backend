package com.propertypro.dto;

import com.propertypro.entity.Expense;
import com.propertypro.entity.MaintenanceRequest;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class OperationsDtos {

    private OperationsDtos() {}

    // ---------------------------------------------------------------- expenses
    public record ExpenseRequest(
            UUID propertyId,
            UUID unitId,
            UUID maintenanceRequestId,
            @NotNull Expense.Category category,
            @NotBlank @Size(max = 255) String description,
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @DecimalMin("0") BigDecimal vatAmount,
            LocalDate expenseDate,
            Expense.ChargeTo chargeTo,
            @Size(max = 10) String paymentMethod,
            @Size(max = 60) String reference) {}

    public record ExpenseResponse(
            UUID id, String expenseNo, UUID propertyId, String propertyName, UUID unitId, String category,
            String description, BigDecimal amount, BigDecimal vatAmount, LocalDate expenseDate, String chargeTo,
            String paymentMethod, String reference, String status, OffsetDateTime approvedAt,
            UUID ownerStatementId, UUID maintenanceRequestId) {

        public static ExpenseResponse from(Expense e) {
            var p = e.getProperty();
            return new ExpenseResponse(e.getId(), e.getExpenseNo(), p == null ? null : p.getId(),
                    p == null ? null : p.getName(), e.getUnitId(), e.getCategory().name(), e.getDescription(),
                    e.getAmount(), e.getVatAmount(), e.getExpenseDate(), e.getChargeTo().name(), e.getPaymentMethod(),
                    e.getReference(), e.getStatus().name(), e.getApprovedAt(), e.getOwnerStatementId(),
                    e.getMaintenanceRequestId());
        }
    }

    public record DecisionRequest(@Size(max = 500) String note) {}

    // ------------------------------------------------------------- maintenance
    public record MaintenanceCreateRequest(
            @NotNull UUID propertyId,
            UUID unitId,
            UUID tenantId,
            @NotBlank @Size(max = 200) String title,
            String description,
            MaintenanceRequest.Category category,
            MaintenanceRequest.Priority priority,
            Expense.ChargeTo chargeTo) {}

    public record AssignRequest(UUID assignedTo, UUID vendorId, LocalDate scheduledDate,
                                @DecimalMin("0") BigDecimal estimatedCost) {}

    public record StatusRequest(
            @NotNull MaintenanceRequest.Status status,
            @Size(max = 2000) String comment,
            @DecimalMin("0") @Digits(integer = 12, fraction = 2) BigDecimal actualCost,
            /** When resolving with a cost charged to the owner, record it as an expense too. */
            boolean createExpense) {}

    public record RatingRequest(@NotNull @Min(1) @Max(5) Short rating) {}

    public record MaintenanceUpdate(OffsetDateTime at, UUID userId, String userName, String statusFrom,
                                    String statusTo, String comment) {}

    public record MaintenanceResponse(
            UUID id, String ticketNo, UUID propertyId, String propertyName, UUID unitId, String unitNo, UUID tenantId,
            String title, String description, String category, String priority, String status,
            UUID assignedTo, UUID vendorId, LocalDate scheduledDate, BigDecimal estimatedCost, BigDecimal actualCost,
            String chargeTo, OffsetDateTime createdAt, OffsetDateTime resolvedAt, List<String> nextStatuses,
            List<MaintenanceUpdate> updates) {}

    // -------------------------------------------------------- owner statements
    public record StatementLineResponse(String lineType, String propertyName, String description, BigDecimal amount) {}

    public record OwnerStatementResponse(
            UUID id, String statementNo, UUID ownerId, String ownerName, LocalDate periodStart, LocalDate periodEnd,
            BigDecimal openingBalance, BigDecimal collections, BigDecimal expenses, BigDecimal managementFee,
            BigDecimal managementFeeVat, BigDecimal adjustments, BigDecimal netPayable, BigDecimal amountRemitted,
            String status, OffsetDateTime approvedAt, OffsetDateTime sentAt, List<StatementLineResponse> lines) {}

    public record RemittanceRequest(
            @NotNull @DecimalMin(value = "0", inclusive = false) @Digits(integer = 12, fraction = 2) BigDecimal amount,
            @NotBlank String method,
            @Size(max = 60) String reference,
            OffsetDateTime paidAt) {}

    public record GenerateResult(String period, int generated, int skippedApproved) {}
}
