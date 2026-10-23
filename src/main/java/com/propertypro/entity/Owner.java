package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.SQLRestriction;

import java.math.BigDecimal;

/** Landlord / property owner managed by the organisation. */
@Getter
@Setter
@Entity
@Table(name = "owners")
@SQLRestriction("deleted_at IS NULL")
public class Owner extends TenantEntity {

    public enum Type { INDIVIDUAL, COMPANY }

    public enum FeeType { PERCENT, FIXED }

    @Enumerated(EnumType.STRING)
    @Column(name = "owner_type", nullable = false)
    private Type ownerType = Type.INDIVIDUAL;

    @Column(nullable = false)
    private String name;

    @Column(name = "id_number")
    private String idNumber;

    @Column(name = "kra_pin")
    private String kraPin;

    private String email;
    private String phone;
    private String address;

    @Column(name = "bank_name")
    private String bankName;

    @Column(name = "bank_branch")
    private String bankBranch;

    @Column(name = "bank_account_name")
    private String bankAccountName;

    @Column(name = "mpesa_phone")
    private String mpesaPhone;

    @Enumerated(EnumType.STRING)
    @Column(name = "management_fee_type", nullable = false)
    private FeeType managementFeeType = FeeType.PERCENT;

    @Column(name = "management_fee_value", nullable = false)
    private BigDecimal managementFeeValue = BigDecimal.ZERO;

    @Column(name = "remittance_day")
    private Short remittanceDay;

    private String notes;
}
