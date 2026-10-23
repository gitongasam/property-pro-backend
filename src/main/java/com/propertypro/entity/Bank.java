package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.hibernate.annotations.Immutable;

/**
 * A bank or Sacco tenants can pay through. Read-only for the app: banks change
 * only through migrations. {@code integration} says how payments are learned
 * about: API (the bank notifies us) or STATEMENT (the landlord uploads it).
 */
@Getter
@Entity
@Immutable
@Table(name = "banks")
public class Bank {

    public enum Integration { API, STATEMENT }

    /** For banks whose tenants pay into "Other bank or Sacco": the landlord enters the Paybill. */
    public static final String OTHER = "OTHER";

    @Id
    private String code;

    private String name;

    /** NULL when each customer has their own Paybill (most Saccos). */
    private String paybill;

    /** What tenants type as the account, e.g. "{account}#{code}". */
    @Column(name = "account_format")
    private String accountFormat;

    @Enumerated(EnumType.STRING)
    private Integration integration;

    @Column(name = "sort_order")
    private int sortOrder;

    private boolean active;

    /** The account tenants type: the landlord's bank account and the unit's payment code in this bank's format. */
    public String accountFor(String bankAccount, String paymentCode) {
        return accountFormat.replace("{account}", bankAccount == null ? "" : bankAccount)
                .replace("{code}", paymentCode == null ? "" : paymentCode);
    }
}
