package com.propertypro.service;

import com.propertypro.dto.BillingDtos.StatementResponse;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A tenant's statement: invoices (charged), payments and credit notes (paid),
 * in date order with a running balance. Void invoices and reversed payments
 * are left out.
 */
public interface StatementService {

    StatementResponse statement(UUID tenantId, LocalDate from, LocalDate to);
}
