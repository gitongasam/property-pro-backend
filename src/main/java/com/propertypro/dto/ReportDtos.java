package com.propertypro.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Dashboard and report rows. */
public final class ReportDtos {

    private ReportDtos() {}

    public record Kpis(BigDecimal billedThisMonth, BigDecimal collectedThisMonth, BigDecimal collectionRatePct,
                       BigDecimal arrearsTotal, BigDecimal arrearsOver90, long tenantsInArrears,
                       long totalUnits, long occupiedUnits, long vacantUnits, BigDecimal occupancyPct,
                       long unmatchedPayments, BigDecimal unmatchedAmount,
                       long openMaintenance, long urgentMaintenance, long leasesEnding60Days) {}

    public record MonthPoint(String month, BigDecimal billed, BigDecimal collected) {}

    public record RecentPayment(UUID id, String tenantName, String unit, BigDecimal amount, String status,
                                String reference, OffsetDateTime paidAt) {}

    public record Dashboard(Kpis kpis, List<MonthPoint> months, List<RecentPayment> recentPayments) {}

    public record ArrearsRow(UUID tenantId, String tenantName, UUID propertyId, String propertyName, String unitNo,
                             BigDecimal notYetDue, BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90,
                             BigDecimal over90, BigDecimal total, LocalDate oldestDue) {}

    public record ArrearsReport(BigDecimal notYetDue, BigDecimal days1to30, BigDecimal days31to60, BigDecimal days61to90,
                                BigDecimal over90, BigDecimal total, List<ArrearsRow> tenants) {}

    public record OccupancyRow(UUID propertyId, String propertyName, long totalUnits, long occupiedUnits,
                               long vacantUnits, BigDecimal occupancyPct, BigDecimal potentialMonthlyRent,
                               BigDecimal actualMonthlyRent, BigDecimal vacancyLoss) {}

    public record RentRollRow(UUID leaseId, String leaseNo, String propertyName, String unitNo, String paymentCode,
                              UUID tenantId, String tenantName, String tenantPhone, String leaseStatus, boolean future,
                              LocalDate startDate, LocalDate endDate, BigDecimal rent, String frequency,
                              LocalDate nextEscalation, BigDecimal balance) {}

    public record CollectionRow(UUID propertyId, String propertyName, BigDecimal billed, BigDecimal collected,
                                BigDecimal collectionRatePct) {}
}
