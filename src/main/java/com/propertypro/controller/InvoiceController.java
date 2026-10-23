package com.propertypro.controller;

import com.propertypro.dto.BillingDtos.BillingRunResult;
import com.propertypro.dto.BillingDtos.InvoiceResponse;
import com.propertypro.dto.BillingDtos.OneOffInvoiceRequest;
import com.propertypro.dto.BillingDtos.SmsQueued;
import com.propertypro.dto.BillingDtos.VoidRequest;
import com.propertypro.dto.BillingDtos.WithholdingTaxRequest;
import com.propertypro.dto.PageResponse;
import com.propertypro.entity.Invoice;
import com.propertypro.scheduler.BillingJobs;
import com.propertypro.service.BillingService;
import com.propertypro.service.PlanGate;
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
import java.util.UUID;

@Tag(name = "Invoices")
@RestController
@RequestMapping("/api/v1/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final BillingService billing;
    private final BillingJobs jobs;
    private final PlanGate planGate;

    @Operation(summary = "List invoices; overdue=true for open invoices past their due date")
    @GetMapping
    @PreAuthorize("hasAuthority('INVOICE_VIEW')")
    public PageResponse<InvoiceResponse> list(
            @RequestParam(required = false) Invoice.Status status,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) UUID propertyId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @PageableDefault(size = 20, sort = "issueDate", direction = Sort.Direction.DESC) Pageable pageable) {
        return billing.list(status, overdue, tenantId, propertyId, from, to, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('INVOICE_VIEW')")
    public InvoiceResponse get(@PathVariable UUID id) {
        return billing.get(id);
    }

    @Operation(summary = "One-off invoice (repairs, parking, any extra charge)")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('INVOICE_CREATE')")
    public InvoiceResponse create(@Valid @RequestBody OneOffInvoiceRequest body) {
        return billing.createOneOff(body);
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('INVOICE_VOID')")
    public InvoiceResponse voidInvoice(@PathVariable UUID id, @Valid @RequestBody VoidRequest body) {
        return billing.voidInvoice(id, body.reason());
    }

    @Operation(summary = "Text the invoice (amount, due date, Paybill and account) to the tenant's phone again")
    @PostMapping("/{id}/send-sms")
    @PreAuthorize("hasAuthority('INVOICE_CREATE')")
    public SmsQueued sendSms(@PathVariable UUID id) {
        return billing.sendToTenant(id);
    }

    @Operation(summary = "Record withholding tax deducted by a commercial tenant")
    @PostMapping("/{id}/withholding-tax")
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public InvoiceResponse withholdingTax(@PathVariable UUID id, @Valid @RequestBody WithholdingTaxRequest body) {
        return billing.recordWithholdingTax(id, body);
    }

    @Operation(summary = "Raise every invoice due up to a date now, instead of waiting for the 05:30 run")
    @PostMapping("/run-billing")
    @PreAuthorize("hasAuthority('INVOICE_CREATE')")
    public BillingRunResult runBilling(@RequestParam(required = false)
                                       @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        planGate.requireWritable();
        return jobs.runNow(date);
    }
}
