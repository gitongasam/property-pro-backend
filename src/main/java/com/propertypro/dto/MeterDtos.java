package com.propertypro.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class MeterDtos {

    private MeterDtos() {}

    public enum Utility { WATER, ELECTRICITY, GAS }

    public record ReadingRequest(
            @NotNull UUID unitId,
            @NotNull Utility utility,
            /** Defaults to today. */
            LocalDate readingDate,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal currentReading,
            /**
             * Leave empty: the last reading of this meter is used. Give it only when the meter was replaced
             * or reset. The first reading of a meter with no previous one is its opening reading (no charge).
             */
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal previousReading) {}

    /** One round of a property: every unit's meter for one utility on one date. All are saved or none. */
    public record BulkReadingRequest(
            @NotNull Utility utility,
            LocalDate readingDate,
            @NotEmpty @Size(max = 500) List<@Valid BulkLine> readings) {}

    public record BulkLine(
            @NotNull UUID unitId,
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal currentReading,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal previousReading) {}

    /** Correct the latest reading of a meter while it is not yet billed. */
    public record ReadingUpdate(
            @NotNull @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal currentReading,
            @DecimalMin("0") @Digits(integer = 10, fraction = 2) BigDecimal previousReading) {}

    public record ReadingResponse(
            UUID id, UUID unitId, String unitNo, UUID propertyId, String propertyName, String utility,
            LocalDate readingDate, BigDecimal previousReading, BigDecimal currentReading, BigDecimal consumption,
            /** Average of this meter's previous three readings; null when there are none. */
            BigDecimal averageConsumption,
            /** Over twice the average (or a likely typo): check before billing. */
            boolean unusual,
            /** A live lease on the unit has a metered charge for this utility, so the next invoice includes it. */
            boolean billable,
            boolean billed, String invoiceNo, String readBy, OffsetDateTime createdAt) {}

    /** A line on the reading sheet: what the caretaker sees before walking the property. */
    public record SheetRow(
            UUID unitId, String unitNo, String tenantName, boolean billable, BigDecimal ratePerUnit,
            LocalDate lastReadingDate, BigDecimal lastReading, BigDecimal lastConsumption) {}
}
