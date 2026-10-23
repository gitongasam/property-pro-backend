package com.propertypro.tenancy;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * The ONLY way to run code outside tenant isolation, or as a specific
 * organisation outside a normal request.
 *
 * <p>Each call runs in a NEW transaction (and therefore a fresh connection), so
 * the database receives the new context. Keep every use easy to find:
 * sign-up, login, M-Pesa webhooks, scheduled jobs, super-admin endpoints.
 */
@Component
public class SystemExecutor {

    private final TransactionTemplate newTransaction;

    public SystemExecutor(PlatformTransactionManager transactionManager) {
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Runs with RLS and the Hibernate tenant filter switched off. */
    public <T> T asSystem(Supplier<T> work) {
        TenantContext.State previous = TenantContext.snapshot();
        TenantContext.setSystem(previous.userId());
        try {
            return newTransaction.execute(status -> work.get());
        } finally {
            TenantContext.restore(previous);
        }
    }

    /** Runs as a normal (isolated) request for the given organisation and user. */
    public <T> T asOrganization(UUID organizationId, UUID userId, Supplier<T> work) {
        TenantContext.State previous = TenantContext.snapshot();
        TenantContext.set(organizationId, userId);
        try {
            return newTransaction.execute(status -> work.get());
        } finally {
            TenantContext.restore(previous);
        }
    }
}
