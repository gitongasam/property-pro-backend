package com.propertypro.service;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Settles invoices with payments and credit notes, oldest due date first.
 * Works in SQL so the payment_allocations trigger (V5) keeps invoice and payment
 * balances right; rows are locked FOR UPDATE so two allocations can't race.
 *
 * <p>Callers holding Invoice or Payment entities must refresh them afterwards:
 * the trigger changes their balances behind Hibernate's back.
 */
public interface AllocationService {

    /** Allocates whatever is unallocated on this payment. Returns the amount allocated now. */
    BigDecimal allocatePayment(UUID paymentId);

    /** Allocates a credit note, to one invoice if given, otherwise to the oldest open invoices. */
    BigDecimal allocateCreditNote(UUID creditNoteId, UUID tenantId, UUID invoiceId, BigDecimal amount);

    /** After new invoices are raised, uses any money the tenant already paid in advance. */
    BigDecimal applyTenantCredit(UUID tenantId);
}
