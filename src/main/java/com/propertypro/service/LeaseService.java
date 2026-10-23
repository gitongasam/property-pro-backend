package com.propertypro.service;

import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.LeasingDtos.ActivateRequest;
import com.propertypro.dto.LeasingDtos.LeaseRequest;
import com.propertypro.dto.LeasingDtos.LeaseResponse;
import com.propertypro.dto.LeasingDtos.NoticeRequest;
import com.propertypro.dto.LeasingDtos.TerminateRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Lease;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Lease life cycle: DRAFT → ACTIVE (unit becomes OCCUPIED, move-in invoice) →
 * NOTICE_GIVEN → TERMINATED (unit becomes VACANT).
 */
public interface LeaseService {

    PageResponse<LeaseResponse> list(Lease.Status status, UUID propertyId, UUID tenantId, Integer endingWithinDays, Pageable pageable);

    LeaseResponse get(UUID id);

    LeaseResponse create(LeaseRequest req);

    /** Only a DRAFT lease can be edited; an active one is changed by ending it and starting another. */
    LeaseResponse update(UUID id, LeaseRequest req);

    /** Also texts the lease terms to the tenant (and issues the move-in invoice unless told not to). */
    LeaseResponse activate(UUID id, ActivateRequest req);

    /** Queues the lease SMS (unit, rent, deposit, how to pay) to the tenant again. */
    SmsQueued sendToTenant(UUID id);

    /**
     * For tenants already living in a unit when the organisation moves onto
     * PropertyPro (imports): the lease goes straight to ACTIVE with no move-in
     * invoice, and regular billing starts on the first billing day after
     * {@code today}. Whatever they already owe is entered as an opening balance.
     */
    Lease importActive(LeaseRequest req, LocalDate today);

    LeaseResponse giveNotice(UUID id, NoticeRequest req);

    LeaseResponse terminate(UUID id, TerminateRequest req);

    Lease find(UUID id);
}
