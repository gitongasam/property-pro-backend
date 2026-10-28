package com.propertypro.controller;

import com.propertypro.dto.MeterDtos.BulkReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingRequest;
import com.propertypro.dto.MeterDtos.ReadingResponse;
import com.propertypro.dto.MeterDtos.ReadingUpdate;
import com.propertypro.dto.MeterDtos.SheetRow;
import com.propertypro.dto.MeterDtos.Utility;
import com.propertypro.dto.PageResponse;
import com.propertypro.service.MeterReadingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Tag(name = "Meter readings")
@RestController
@RequestMapping("/api/v1/meter-readings")
@RequiredArgsConstructor
public class MeterReadingController {

    private static final String VIEW = "hasAnyAuthority('METER_READING_RECORD','INVOICE_VIEW')";

    private final MeterReadingService readings;

    @GetMapping
    @PreAuthorize(VIEW)
    public PageResponse<ReadingResponse> list(@RequestParam(required = false) UUID propertyId,
                                              @RequestParam(required = false) UUID unitId,
                                              @RequestParam(required = false) Utility utility,
                                              @RequestParam(required = false) Boolean billed,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                              @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                              @RequestParam(defaultValue = "0") @Min(0) int page,
                                              @RequestParam(defaultValue = "50") @Min(1) @Max(100) int size) {
        return readings.list(propertyId, unitId, utility, billed, from, to, page, size);
    }

    @Operation(summary = "Every unit of a property with its last reading, to fill in on a meter round")
    @GetMapping("/sheet")
    @PreAuthorize(VIEW)
    public List<SheetRow> sheet(@RequestParam UUID propertyId, @RequestParam Utility utility) {
        return readings.sheet(propertyId, utility);
    }

    @GetMapping("/{id}")
    @PreAuthorize(VIEW)
    public ReadingResponse get(@PathVariable UUID id) {
        return readings.get(id);
    }

    @Operation(summary = "Record one reading; billed on the tenant's next invoice when the lease has a metered charge")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('METER_READING_RECORD')")
    public ReadingResponse record(@Valid @RequestBody ReadingRequest body) {
        return readings.record(body);
    }

    @Operation(summary = "Record a whole round (one utility, one date, many units); all are saved or none")
    @PostMapping("/bulk")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('METER_READING_RECORD')")
    public List<ReadingResponse> recordBulk(@Valid @RequestBody BulkReadingRequest body) {
        return readings.recordBulk(body);
    }

    @Operation(summary = "Correct a meter's latest reading before it is billed")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('METER_READING_RECORD')")
    public ReadingResponse update(@PathVariable UUID id, @Valid @RequestBody ReadingUpdate body) {
        return readings.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('METER_READING_RECORD')")
    public void delete(@PathVariable UUID id) {
        readings.delete(id);
    }
}
