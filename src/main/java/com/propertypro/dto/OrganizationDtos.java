package com.propertypro.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Request and response bodies for Settings → Organisation. */
public final class OrganizationDtos {

    private OrganizationDtos() {}

    public record Defaults(
            /** VAT charged on the management fee in owner statements (Kenya: 16). */
            @DecimalMin("0") @DecimalMax("100") BigDecimal managementFeeVatRate,
            @Min(1) @Max(28) Integer defaultBillingDay,
            @Min(0) @Max(60) Integer defaultDueDays,
            /** Days before the due date to send rent reminders. */
            @Min(0) @Max(30) Integer reminderDaysBefore,
            @Size(max = 11) String smsSenderId) {}

    public record ProfileRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 200) String legalName,
            @Pattern(regexp = "^[AP]\\d{9}[A-Z]$", message = "must look like P051234567X") String kraPin,
            @Email @Size(max = 200) String email,
            @Size(max = 20) String phone,
            String address,
            @Size(max = 60) String county,
            @Size(max = 60) String town,
            @Size(max = 500) String logoUrl,
            @Valid Defaults defaults) {}

    public record ProfileResponse(UUID id, String name, String slug, String legalName, String kraPin, String email,
                                  String phone, String address, String county, String town, String logoUrl,
                                  String currency, String timezone, String status, Defaults defaults,
                                  OffsetDateTime createdAt) {}
}
