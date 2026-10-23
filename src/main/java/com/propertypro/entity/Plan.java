package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

/** Subscription plan. Read-only for the app: plans change only through migrations. */
@Getter
@Entity
@Immutable
@Table(name = "plans")
public class Plan {

    @Id
    private UUID id;

    private String code;
    private String name;
    private String description;
    private String currency;

    @Column(name = "monthly_price")
    private BigDecimal monthlyPrice;

    @Column(name = "annual_price")
    private BigDecimal annualPrice;

    /** NULL = unlimited */
    @Column(name = "unit_limit")
    private Integer unitLimit;

    /** NULL = unlimited */
    @Column(name = "user_limit")
    private Integer userLimit;

    /** NULL = the unit limit is a hard limit (no paid overage) */
    @Column(name = "extra_unit_price")
    private BigDecimal extraUnitPrice;

    @Column(name = "sms_quota_monthly")
    private int smsQuotaMonthly;

    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Boolean> features;

    @Column(name = "is_public")
    private boolean isPublic;

    private boolean active;

    public boolean hasFeature(String feature) {
        return features != null && Boolean.TRUE.equals(features.get(feature));
    }
}
