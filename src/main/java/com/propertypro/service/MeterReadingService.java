package com.propertypro.service;

import com.propertypro.dto.MeterDtos.BulkReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingResponse;
import com.propertypro.dto.MeterDtos.ReadingUpdate;
import com.propertypro.dto.MeterDtos.SheetRow;
import com.propertypro.dto.MeterDtos.Utility;
import com.propertypro.dto.PageResponse;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Water, electricity and gas meter readings per unit. BillingService puts each unbilled
 * reading on the tenant's next invoice when the lease has a metered charge for that utility.
 * Readings of one meter form a chain: each one's previous reading is the last one's current.
 */
public interface MeterReadingService {

    PageResponse<ReadingResponse> list(UUID propertyId, UUID unitId, Utility utility, Boolean billed,
                                       LocalDate from, LocalDate to, int page, int size);

    ReadingResponse get(UUID id);

    ReadingResponse record(ReadingRequest req);

    List<ReadingResponse> recordBulk(BulkReadingRequest req);

    /** Every unit of a property with its last reading, to fill in on a round. */
    List<SheetRow> sheet(UUID propertyId, Utility utility);

    /** Only the latest reading of a meter, and only before it is billed. */
    ReadingResponse update(UUID id, ReadingUpdate req);

    void delete(UUID id);
}
