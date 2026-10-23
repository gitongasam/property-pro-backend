package com.propertypro.controller;

import com.propertypro.dto.BillingDtos.CreditNoteRequest;
import com.propertypro.dto.BillingDtos.CreditNoteResponse;
import com.propertypro.service.BillingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Invoices")
@RestController
@RequestMapping("/api/v1/credit-notes")
@RequiredArgsConstructor
public class CreditNoteController {

    private final BillingService billing;

    @Operation(summary = "Credit a tenant (to one invoice, or their oldest open invoices)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('CREDIT_NOTE_CREATE')")
    public CreditNoteResponse create(@Valid @RequestBody CreditNoteRequest body) {
        return billing.createCreditNote(body);
    }
}
