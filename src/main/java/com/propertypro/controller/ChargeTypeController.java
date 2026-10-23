package com.propertypro.controller;

import com.propertypro.dto.LeasingDtos.ChargeTypeResponse;
import com.propertypro.repository.ChargeTypeRepository;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Charge types")
@RestController
@RequestMapping("/api/v1/charge-types")
@RequiredArgsConstructor
public class ChargeTypeController {

    private final ChargeTypeRepository chargeTypes;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('LEASE_VIEW','INVOICE_VIEW')")
    @Transactional(readOnly = true)
    public List<ChargeTypeResponse> list() {
        return chargeTypes.findByActiveTrueOrderByAllocationPriorityAsc().stream().map(ChargeTypeResponse::from).toList();
    }
}
