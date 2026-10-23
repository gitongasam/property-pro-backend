package com.propertypro.controller;

import com.propertypro.dto.ReportDtos.ArrearsReport;
import com.propertypro.dto.ReportDtos.CollectionRow;
import com.propertypro.dto.ReportDtos.Dashboard;
import com.propertypro.dto.ReportDtos.OccupancyRow;
import com.propertypro.dto.ReportDtos.RentRollRow;
import com.propertypro.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Reports")
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportController {

    private final ReportService reports;

    @Operation(summary = "Dashboard: headline figures, 12-month billed vs collected, latest payments")
    @GetMapping("/dashboard")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public Dashboard dashboard() {
        return reports.dashboard();
    }

    @Operation(summary = "Arrears ageing by tenant: not yet due, 1–30, 31–60, 61–90, over 90 days")
    @GetMapping("/arrears")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public ArrearsReport arrears(@RequestParam(required = false) UUID propertyId) {
        return reports.arrears(propertyId);
    }

    @GetMapping("/occupancy")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<OccupancyRow> occupancy() {
        return reports.occupancy();
    }

    @GetMapping("/rent-roll")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<RentRollRow> rentRoll(@RequestParam(required = false) UUID propertyId) {
        return reports.rentRoll(propertyId);
    }

    @Operation(summary = "Billed against collected per property for a date range (default: this month)")
    @GetMapping("/collections")
    @PreAuthorize("hasAuthority('REPORT_VIEW')")
    public List<CollectionRow> collections(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        LocalDate start = from == null ? LocalDate.now().withDayOfMonth(1) : from;
        LocalDate end = to == null ? start.plusMonths(1).minusDays(1) : to;
        return reports.collections(start, end);
    }
}
