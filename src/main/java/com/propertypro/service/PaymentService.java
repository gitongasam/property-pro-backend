package com.propertypro.service;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PaymentDtos.ManualPaymentRequest;
import com.propertypro.dto.PaymentDtos.PaymentResponse;
import com.propertypro.dto.PaymentDtos.Suggestion;
import com.propertypro.entity.Payment;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface PaymentService {

    PageResponse<PaymentResponse> list(Payment.Status status, UUID tenantId, Payment.Method method, LocalDate from, LocalDate to, Pageable pageable);

    PaymentResponse get(UUID id);

    PaymentResponse recordManual(ManualPaymentRequest req);

    /** Creates the payment for an incoming M-Pesa transaction and matches it if it can. */
    Payment recordMpesa(UUID channelId, Payment.Source source, String receipt, BigDecimal amount, OffsetDateTime paidAt, String payerName, String payerPhone, String billRef, UUID knownTenantId);

    /**
     * Creates the payment for a credit to the landlord's bank account (bank notification or statement line) and
     * matches it if it can. The account may carry the bank account first: 1234567890#KLM-B4 matches unit KLM-B4.
     */
    Payment recordBank(UUID channelId, Payment.Source source, String bankReference, BigDecimal amount, OffsetDateTime paidAt, String payerName, String payerPhone, String accountTyped);

    /** Staff tell us who an unmatched payment belongs to. */
    PaymentResponse match(UUID paymentId, UUID tenantId);

    /** Undo a payment (bounced cheque, wrong entry): its allocations are removed and invoices reopen. */
    PaymentResponse reverse(UUID paymentId, String reason);

    /** Likely owners of an unmatched payment, best first. */
    List<Suggestion> suggestions(UUID paymentId);

    Payment find(UUID id);
}
