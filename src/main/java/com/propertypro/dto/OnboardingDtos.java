package com.propertypro.dto;

import jakarta.validation.constraints.Pattern;

import java.time.OffsetDateTime;
import java.util.List;

/** Request and response bodies for the onboarding checklist. */
public final class OnboardingDtos {

    private OnboardingDtos() {}

    public record ProfileRequest(
            @Pattern(regexp = "LANDLORD|FIRM|AGENCY") String businessType,
            @Pattern(regexp = "1-30|31-150|151-600|600\\+") String unitsRange,
            @Pattern(regexp = "RESIDENTIAL|COMMERCIAL|MIXED") String portfolioFocus) {}

    public record StepStatus(String key, String title, boolean optional, String status, long count) {}

    public record TrialInfo(String status, OffsetDateTime trialEndsAt, Long daysLeft, boolean readOnly) {}

    public record OnboardingStatus(String businessType, String unitsRange, String portfolioFocus,
                                   List<StepStatus> steps, int percent, String nextStep,
                                   boolean canComplete, boolean completed, OffsetDateTime completedAt,
                                   boolean mpesaVerified, TrialInfo trial) {}
}
