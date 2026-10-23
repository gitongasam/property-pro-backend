package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

/** A renter (person or company). Named "tenant" as in property management, not SaaS tenancy. */
@Getter
@Setter
@Entity
@Table(name = "tenants")
@SQLRestriction("deleted_at IS NULL")
public class Tenant extends TenantEntity {

    public enum Type { INDIVIDUAL, COMPANY }

    public enum IdType { NATIONAL_ID, PASSPORT, ALIEN_ID, COMPANY_REG }

    public enum Status { PROSPECT, ACTIVE, FORMER, BLACKLISTED }

    @Enumerated(EnumType.STRING)
    @Column(name = "tenant_type", nullable = false)
    private Type tenantType = Type.INDIVIDUAL;

    @Column(nullable = false)
    private String name;

    @Column(name = "contact_person")
    private String contactPerson;

    @Enumerated(EnumType.STRING)
    @Column(name = "id_type")
    private IdType idType;

    /** AES-GCM encrypted by CryptoService. */
    @Column(name = "id_number_enc")
    private byte[] idNumberEnc;

    @Column(name = "id_number_hash")
    private String idNumberHash;

    @Column(name = "kra_pin")
    private String kraPin;

    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "alt_phone")
    private String altPhone;

    private String employer;

    @Column(name = "postal_address")
    private String postalAddress;

    @Column(name = "next_of_kin_name")
    private String nextOfKinName;

    @Column(name = "next_of_kin_phone")
    private String nextOfKinPhone;

    @Column(name = "next_of_kin_relationship")
    private String nextOfKinRelationship;

    /** Fallback Paybill account number for this tenant (the unit's code is the usual one). */
    @Column(name = "account_code", nullable = false, updatable = false)
    private String accountCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    private String notes;
}
