package com.propertypro.dto;

import com.propertypro.dto.AuthDtos.AuthResponse;
import com.propertypro.dto.AuthDtos.LoginRequest;
import com.propertypro.dto.AuthDtos.MeResponse;
import com.propertypro.dto.AuthDtos.SignupRequest;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Request and response bodies for /api/v1/auth. */
public final class AuthDtos {

    private AuthDtos() {}

    /** Kenyan mobile numbers in E.164: +2547XXXXXXXX or +2541XXXXXXXX. */
    static final String KE_PHONE = "^\\+254[17]\\d{8}$";

    public record SignupRequest(
            @NotBlank @Size(max = 150) String organizationName,
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @NotBlank @Email @Size(max = 200) String email,
            @NotBlank @Pattern(regexp = KE_PHONE, message = "must be a Kenyan number like +254712345678") String phone,
            @NotBlank @Size(min = 8, max = 72, message = "must be 8 to 72 characters") String password,
            @Size(max = 60) String county,
            @Size(max = 80) String town) {}

    public record LoginRequest(
            @NotBlank @Email String email,
            @NotBlank String password,
            /** Needed only when the same email belongs to more than one organisation. */
            String organizationSlug) {}

    public record RefreshRequest(@NotBlank String refreshToken) {}

    public record AcceptInviteRequest(@NotBlank String token, @NotBlank @Size(min = 8, max = 72) String password) {}

    public record AuthResponse(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            MeResponse user) {}

    public record MeResponse(
            UUID id,
            String firstName,
            String lastName,
            String email,
            String phone,
            String userType,
            List<String> roles,
            List<String> permissions,
            OrganizationSummary organization,
            PlanSummary plan,
            /** False until the SMS code is entered; the app then only allows /api/v1/auth/**. */
            boolean phoneVerified) {}

    public record OrganizationSummary(UUID id, String name, String slug, String status) {}

    public record PlanSummary(
            String code,
            String name,
            String subscriptionStatus,
            OffsetDateTime trialEndsAt,
            Integer unitLimit,
            Integer userLimit,
            Map<String, Boolean> features) {}

    public record ClientInfo(String userAgent, String ipAddress) {}

    public record VerifyPhoneRequest(@NotBlank @Pattern(regexp = "^\\s*\\d{6}\\s*$", message = "must be the 6-digit code") String code) {}

    public record CodeSentResponse(String phone, long expiresInSeconds, long resendInSeconds) {}
}
