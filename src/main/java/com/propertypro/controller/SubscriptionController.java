package com.propertypro.controller;

import com.propertypro.dto.SubscriptionDtos.PaymentView;
import com.propertypro.dto.SubscriptionDtos.SubscriptionView;
import com.propertypro.dto.SubscriptionDtos.UpgradeRequest;
import com.propertypro.dto.SubscriptionDtos.UpgradeResponse;
import com.propertypro.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@Tag(name = "Subscription")
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
public class SubscriptionController {

    private final SubscriptionService service;

    @Operation(summary = "The organisation's current plan, period and trial end")
    @GetMapping
    public SubscriptionView current() {
        return service.current();
    }

    @Operation(summary = "Pay for a plan: sends an M-Pesa prompt (KCB STK Push) to the phone; poll the payment for the result")
    @PostMapping("/upgrade")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGE')")
    public UpgradeResponse upgrade(@Valid @RequestBody UpgradeRequest body) {
        return service.upgrade(body);
    }

    @Operation(summary = "A subscription payment: PENDING until the customer answers the prompt, then SUCCESS or FAILED")
    @GetMapping("/payments/{id}")
    @PreAuthorize("hasAuthority('SUBSCRIPTION_MANAGE')")
    public PaymentView payment(@PathVariable UUID id) {
        return service.payment(id);
    }
}
