package com.propertypro.service;

import com.propertypro.dto.ReportDtos.ArrearsReport;
import com.propertypro.dto.ReportDtos.CollectionRow;
import com.propertypro.dto.ReportDtos.Dashboard;
import com.propertypro.dto.ReportDtos.OccupancyRow;
import com.propertypro.dto.ReportDtos.RentRollRow;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Read-only figures for the dashboard and Reports screens. Plain SQL over the
 * reporting views (V8); RLS keeps every query to the caller's organisation.
 */
public interface ReportService {

    Dashboard dashboard();

    ArrearsReport arrears(UUID propertyId);

    List<OccupancyRow> occupancy();

    List<RentRollRow> rentRoll(UUID propertyId);

    /** Billed (by invoice date) against collected (allocated from payments made in the period), per property. */
    List<CollectionRow> collections(LocalDate from, LocalDate to);
}
