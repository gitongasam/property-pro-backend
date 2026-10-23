package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** A SaaS customer (landlord, agency, management firm). Isolated by RLS on its own id. */
@Getter
@Setter
@Entity
@Table(name = "organizations")
public class Organization {

    public enum Status { TRIAL, ACTIVE, PAST_DUE, SUSPENDED, CANCELLED }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    private String name;
    private String slug;

    @Column(name = "legal_name")
    private String legalName;

    @Column(name = "kra_pin")
    private String kraPin;

    private String email;
    private String phone;
    private String address;
    private String county;
    private String town;

    @Column(name = "logo_url")
    private String logoUrl;

    private String currency = "KES";
    private String timezone = "Africa/Nairobi";

    @Enumerated(EnumType.STRING)
    private Status status = Status.TRIAL;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> settings = new HashMap<>();

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "deleted_at")
    private OffsetDateTime deletedAt;
}
