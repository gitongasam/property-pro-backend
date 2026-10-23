package com.propertypro.entity;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.TenantId;

import java.util.UUID;

/**
 * Base for organisation-owned tables that do NOT have the full
 * created_at / updated_at / created_by / deleted_at set. Subclasses map the
 * timestamp columns their table actually has.
 */
@Getter
@Setter
@MappedSuperclass
public abstract class TenantRow {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @TenantId
    @Column(name = "organization_id", nullable = false, updatable = false)
    private UUID organizationId;
}
