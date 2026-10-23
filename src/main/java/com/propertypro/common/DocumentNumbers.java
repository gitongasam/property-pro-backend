package com.propertypro.common;

import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

/**
 * Per-organisation, gap-free document numbers (INV-2026-000123) from the
 * next_document_number() function in V5. Must be called inside the
 * transaction that saves the document, so a rollback also releases the number.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumbers {

    public enum Type { INVOICE, CREDIT_NOTE, RECEIPT, LEASE, STATEMENT, EXPENSE }

    private final JdbcClient jdbc;

    public String next(Type type) {
        return next(type, LocalDate.now());
    }

    public String next(Type type, LocalDate date) {
        return jdbc.sql("SELECT next_document_number(?, ?, ?)")
                .param(TenantContext.requireOrganizationId())
                .param(type.name())
                .param(date)
                .query(String.class)
                .single();
    }

    /** Maintenance tickets use their own short format: MNT-0001. */
    public String nextTicket() {
        Long n = jdbc.sql("SELECT count(*) + 1 FROM maintenance_requests").query(Long.class).single();
        return String.format("MNT-%04d", n);
    }
}
