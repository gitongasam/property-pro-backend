package com.propertypro.tenancy;

import org.hibernate.cfg.AvailableSettings;
import org.hibernate.context.spi.CurrentTenantIdentifierResolver;
import org.springframework.boot.autoconfigure.orm.jpa.HibernatePropertiesCustomizer;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * First layer of isolation: Hibernate adds {@code organization_id = ?} to every
 * query on entities with a {@code @TenantId} field, and fills that field on insert.
 * (The second layer is PostgreSQL RLS.)
 */
@Component
public class TenantIdentifierResolver implements CurrentTenantIdentifierResolver<UUID>, HibernatePropertiesCustomizer {

    /** No organisation in context: matches no rows (fail closed). */
    static final UUID NONE = new UUID(0L, 0L);
    /** System context: Hibernate skips the tenant filter. */
    static final UUID SYSTEM = new UUID(0L, 1L);

    @Override
    public UUID resolveCurrentTenantIdentifier() {
        if (TenantContext.isSystem()) {
            return SYSTEM;
        }
        return TenantContext.organizationId().orElse(NONE);
    }

    @Override
    public boolean validateExistingCurrentSessions() {
        return false;
    }

    /** Hibernate 6.4+: the root tenant sees all rows. */
    public boolean isRoot(UUID tenantId) {
        return SYSTEM.equals(tenantId);
    }

    @Override
    public void customize(Map<String, Object> hibernateProperties) {
        hibernateProperties.put(AvailableSettings.MULTI_TENANT_IDENTIFIER_RESOLVER, this);
    }
}
