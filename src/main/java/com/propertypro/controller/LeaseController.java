package com.propertypro.controller;

import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.LeasingDtos.ActivateRequest;
import com.propertypro.dto.LeasingDtos.LeaseRequest;
import com.propertypro.dto.LeasingDtos.LeaseResponse;
import com.propertypro.dto.LeasingDtos.NoticeRequest;
import com.propertypro.dto.LeasingDtos.TerminateRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Lease;
import com.propertypro.service.LeaseService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Leases")
@RestController
@RequestMapping("/api/v1/leases")
@RequiredArgsConstructor
public class LeaseController {

    private final LeaseService leases;

    @Operation(summary = "List leases; endingWithinDays=60 lists live leases ending soon")
    @GetMapping
    @PreAuthorize("hasAuthority('LEASE_VIEW')")
    public PageResponse<LeaseResponse> list(@RequestParam(required = false) Lease.Status status,
                                            @RequestParam(required = false) UUID propertyId,
                                            @RequestParam(required = false) UUID tenantId,
                                            @RequestParam(required = false) Integer endingWithinDays,
                                            @PageableDefault(size = 20, sort = "startDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return leases.list(status, propertyId, tenantId, endingWithinDays, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('LEASE_VIEW')")
    public LeaseResponse get(@PathVariable UUID id) {
        return leases.get(id);
    }

    @Operation(summary = "Create a draft lease (nothing is billed until it is activated)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('LEASE_MANAGE')")
    public LeaseResponse create(@Valid @RequestBody LeaseRequest body) {
        return leases.create(body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('LEASE_MANAGE')")
    public LeaseResponse update(@PathVariable UUID id, @Valid @RequestBody LeaseRequest body) {
        return leases.update(id, body);
    }

    @Operation(summary = "Activate: unit becomes occupied, move-in invoice (deposit + first period) is issued, lease terms are texted to the tenant")
    @PostMapping("/{id}/activate")
    @PreAuthorize("hasAuthority('LEASE_MANAGE')")
    public LeaseResponse activate(@PathVariable UUID id, @RequestBody(required = false) ActivateRequest body) {
        return leases.activate(id, body);
    }

    @Operation(summary = "Text the lease terms (unit, rent, deposit, how to pay) to the tenant's phone again")
    @PostMapping("/{id}/send-sms")
    @PreAuthorize("hasAuthority('LEASE_MANAGE')")
    public SmsQueued sendSms(@PathVariable UUID id) {
        return leases.sendToTenant(id);
    }

    @PostMapping("/{id}/notice")
    @PreAuthorize("hasAuthority('LEASE_MANAGE')")
    public LeaseResponse notice(@PathVariable UUID id, @Valid @RequestBody NoticeRequest body) {
        return leases.giveNotice(id, body);
    }

    @Operation(summary = "End the lease: unit becomes vacant, billing stops")
    @PostMapping("/{id}/terminate")
    @PreAuthorize("hasAuthority('LEASE_TERMINATE')")
    public LeaseResponse terminate(@PathVariable UUID id, @Valid @RequestBody TerminateRequest body) {
        return leases.terminate(id, body);
    }
}
