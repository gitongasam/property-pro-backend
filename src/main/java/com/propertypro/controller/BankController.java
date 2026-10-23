package com.propertypro.controller;

import com.propertypro.dto.BankDtos.BankResponse;
import com.propertypro.service.BankPaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "Settings")
@RestController
@RequestMapping("/api/v1/banks")
@RequiredArgsConstructor
public class BankController {

    private final BankPaymentService bankPayments;

    @Operation(summary = "Banks and Saccos tenants can pay through, their Paybill, and whether payments arrive "
            + "automatically or by statement upload")
    @GetMapping
    public List<BankResponse> list() {
        return bankPayments.banks();
    }
}
