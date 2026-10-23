package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.SQLRestriction;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@Entity
@Table(name = "properties")
@SQLRestriction("deleted_at IS NULL")
public class Property extends TenantEntity {

    public enum Type { RESIDENTIAL, COMMERCIAL, MIXED_USE, INDUSTRIAL, LAND }

    public enum Status { ACTIVE, INACTIVE }

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id")
    private Owner owner;

    /** Short code used in unit payment codes, e.g. KLM -> KLM-B4. */
    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "property_type", nullable = false)
    private Type propertyType;

    private String county;
    private String town;
    private String estate;
    private String address;
    private BigDecimal latitude;
    private BigDecimal longitude;

    @Column(name = "lr_number")
    private String lrNumber;

    @Column(name = "year_built")
    private Short yearBuilt;

    private Short floors;
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> amenities = new ArrayList<>();

    @Column(name = "cover_photo_url")
    private String coverPhotoUrl;

    /** Overrides the owner's fee for this property when set. */
    @Enumerated(EnumType.STRING)
    @Column(name = "management_fee_type")
    private Owner.FeeType managementFeeType;

    @Column(name = "management_fee_value")
    private BigDecimal managementFeeValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;
}
