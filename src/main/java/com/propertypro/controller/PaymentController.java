package com.propertypro.controller;

import com.propertypro.dto.PageResponse;
import com.propertypro.dto.PaymentDtos.ManualPaymentRequest;
import com.propertypro.dto.PaymentDtos.MatchRequest;
import com.propertypro.dto.PaymentDtos.PaymentResponse;
import com.propertypro.dto.PaymentDtos.ReverseRequest;
import com.propertypro.dto.PaymentDtos.StkPushRequest;
import com.propertypro.dto.PaymentDtos.StkPushResponse;
import com.propertypro.dto.PaymentDtos.Suggestion;
import com.propertypro.entity.Payment;
import com.propertypro.service.PaymentChannelService;
import com.propertypro.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Payments")
@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService payments;
    private final PaymentChannelService channels;

    @Operation(summary = "List payments; status=UNMATCHED is the reconciliation queue")
    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    public PageResponse<PaymentResponse> list(
            @RequestParam(required = false) Payment.Status status,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) Payment.Method method,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "paidAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return payments.list(status, tenantId, method, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENT_VIEW')")
    public PaymentResponse get(@PathVariable UUID id) {
        return payments.get(id);
    }

    @Operation(summary = "Record a cash, cheque, bank or hand-entered M-Pesa payment")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public PaymentResponse record(@Valid @RequestBody ManualPaymentRequest body) {
        return payments.recordManual(body);
    }

    @Operation(summary = "Tenants an unmatched payment probably belongs to, best first")
    @GetMapping("/{id}/suggestions")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    public List<Suggestion> suggestions(@PathVariable UUID id) {
        return payments.suggestions(id);
    }

    @Operation(summary = "Match an unmatched payment to a tenant; it is allocated to their oldest invoices")
    @PostMapping("/{id}/match")
    @PreAuthorize("hasAuthority('PAYMENT_RECONCILE')")
    public PaymentResponse match(@PathVariable UUID id, @Valid @RequestBody MatchRequest body) {
        return payments.match(id, body.tenantId());
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('PAYMENT_REVERSE')")
    public PaymentResponse reverse(@PathVariable UUID id, @Valid @RequestBody ReverseRequest body) {
        return payments.reverse(id, body.reason());
    }

    @Operation(summary = "Send an M-Pesa payment prompt (STK Push) to a tenant's phone")
    @PostMapping("/stk-push")
    @PreAuthorize("hasAnyAuthority('PAYMENT_RECORD','PORTAL_TENANT')")
    public StkPushResponse stkPush(@Valid @RequestBody StkPushRequest body) {
        return channels.stkPush(body);
    }
}
