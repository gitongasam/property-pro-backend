package com.propertypro.controller;

import com.propertypro.dto.BillingDtos.StatementResponse;
import com.propertypro.service.StatementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@Tag(name = "Tenants")
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/statement")
@RequiredArgsConstructor
public class StatementController {

    private final StatementService statements;

    @Operation(summary = "Tenant statement with running balance (default: last three months)")
    @GetMapping
    @PreAuthorize("hasAuthority('INVOICE_VIEW')")
    public StatementResponse statement(@PathVariable UUID tenantId,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                       @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return statements.statement(tenantId, from, to);
    }
}
