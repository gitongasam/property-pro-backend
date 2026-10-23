package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A login. Not a {@code @TenantId} entity because platform (super admin) users
 * have no organisation; RLS still isolates users by organisation.
 */
@Getter
@Setter
@Entity
@Table(name = "users")
public class User {

    public enum Type { PLATFORM, STAFF, TENANT, OWNER }

    public enum Status { INVITED, ACTIVE, DISABLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** NULL only for platform users. */
    @Column(name = "organization_id", updatable = false)
    private UUID organizationId;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_type", nullable = false, updatable = false)
    private Type userType;

    @Column(name = "first_name", nullable = false)
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    private String email;
    private String phone;

    @Column(name = "password_hash")
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private Status status = Status.INVITED;

    @Column(name = "email_verified_at")
    private OffsetDateTime emailVerifiedAt;

    @Column(name = "phone_verified_at")
    private OffsetDateTime phoneVerifiedAt;

    @Column(name = "mfa_enabled")
    private boolean mfaEnabled;

    @Column(name = "last_login_at")
    private OffsetDateTime lastLoginAt;

    /** Set for OWNER users (owner portal). */
    @Column(name = "owner_id")
    private UUID ownerId;

    /** Set for TENANT users (tenant portal). */
    @Column(name = "tenant_id")
    private UUID tenantId;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;

    @PrePersist
    void normalise() {
        if (email != null) {
            email = email.trim().toLowerCase();
        }
    }

    public String fullName() {
        return lastName == null || lastName.isBlank() ? firstName : firstName + " " + lastName;
    }
}
