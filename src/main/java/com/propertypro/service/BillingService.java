package com.propertypro.service;

import com.propertypro.dto.BillingDtos.CreditNoteRequest;
import com.propertypro.dto.BillingDtos.CreditNoteResponse;
import com.propertypro.dto.BillingDtos.InvoiceResponse;
import com.propertypro.dto.BillingDtos.OneOffInvoiceRequest;
import com.propertypro.dto.BillingDtos.PayTo;
import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.BillingDtos.WithholdingTaxRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Invoice;
import com.propertypro.entity.Lease;
import com.propertypro.entity.Tenant;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Raises invoices: move-in, scheduled (per billing period), one-off, penalties,
 * opening balances; voids them; issues credit notes.
 */
public interface BillingService {

    PageResponse<InvoiceResponse> list(Invoice.Status status, boolean overdueOnly, UUID tenantId, UUID propertyId, LocalDate from, LocalDate to, Pageable pageable);

    InvoiceResponse get(UUID id);

    Invoice find(UUID id);

    Invoice createMoveInInvoice(Lease lease, LocalDate from, LocalDate to, BigDecimal firstPeriodFactor);

    List<Invoice> billLease(UUID leaseId, LocalDate runDate);

    InvoiceResponse createOneOff(OneOffInvoiceRequest req);

    /** What a tenant already owed when the organisation started using PropertyPro. */
    Invoice createOpeningBalance(Tenant tenant, Lease lease, BigDecimal amount, LocalDate asOf);

    int applyPenalties(LocalDate today);

    InvoiceResponse voidInvoice(UUID id, String reason);

    /** A commercial tenant paid net of withholding tax and sent the certificate. */
    InvoiceResponse recordWithholdingTax(UUID id, WithholdingTaxRequest req);

    CreditNoteResponse createCreditNote(CreditNoteRequest req);

    /**
     * The Paybill and account tenants of this property pay with: a property-specific channel first, else the
     * organisation's. On a bank Paybill the account is the bank account plus the unit code (1234567890#KLM-B4).
     */
    PayTo payTo(Invoice inv);

    /** Where the tenant on this lease pays: the property's (or organisation's) Paybill and the unit's code. */
    PayTo payTo(Lease lease);

    /** Queues the invoice SMS (and email, if the tenant has one) to the tenant again. */
    SmsQueued sendToTenant(UUID invoiceId);
}
