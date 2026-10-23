package com.propertypro.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Request and response bodies for team management. */
public final class TeamDtos {

    private TeamDtos() {}

    public record InviteRequest(
            @NotBlank @Size(max = 80) String firstName,
            @Size(max = 80) String lastName,
            @NotBlank @Email @Size(max = 200) String email,
            @Pattern(regexp = "^\\+254[17]\\d{8}$", message = "must look like +254712345678") String phone,
            @NotBlank String roleCode) {}

    public record RoleRequest(@NotBlank String roleCode) {}

    public record Member(UUID id, String firstName, String lastName, String email, String phone, String status,
                         List<String> roles, OffsetDateTime lastLoginAt, OffsetDateTime createdAt, boolean you) {}

    public record InviteResult(Member member, String inviteLink, OffsetDateTime expiresAt) {}

    public record RoleOption(String code, String name, String description) {}
}
