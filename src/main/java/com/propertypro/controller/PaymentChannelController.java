package com.propertypro.controller;

import com.propertypro.dto.BankDtos.StatementImportResult;
import com.propertypro.dto.PaymentDtos.ChannelRequest;
import com.propertypro.dto.PaymentDtos.ChannelResponse;
import com.propertypro.service.BankPaymentService;
import com.propertypro.service.PaymentChannelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@Tag(name = "Settings")
@RestController
@RequestMapping("/api/v1/payment-channels")
@RequiredArgsConstructor
public class PaymentChannelController {

    private final PaymentChannelService channels;
    private final BankPaymentService bankPayments;

    @GetMapping
    @PreAuthorize("hasAuthority('PAYMENT_CHANNEL_MANAGE')")
    public List<ChannelResponse> list() {
        return channels.list();
    }

    @Operation(summary = "Add a Paybill, Till, bank Paybill (KCB 522522) or bank account. Keys and account numbers are stored encrypted")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PAYMENT_CHANNEL_MANAGE')")
    public ChannelResponse create(@Valid @RequestBody ChannelRequest body) {
        return channels.create(body);
    }

    @Operation(summary = "Update; blank secret fields keep the stored values")
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PAYMENT_CHANNEL_MANAGE')")
    public ChannelResponse update(@PathVariable UUID id, @Valid @RequestBody ChannelRequest body) {
        return channels.update(id, body);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAuthority('PAYMENT_CHANNEL_MANAGE')")
    public void deactivate(@PathVariable UUID id) {
        channels.deactivate(id);
    }

    @Operation(summary = "Register this channel's callback URLs with Safaricom (C2B)")
    @PostMapping("/{id}/register-urls")
    @PreAuthorize("hasAuthority('PAYMENT_CHANNEL_MANAGE')")
    public ChannelResponse registerUrls(@PathVariable UUID id) {
        return channels.registerUrls(id);
    }

    @Operation(summary = "Upload a bank statement (CSV) to record the rent paid into this bank account. "
            + "dryRun=true only reads and matches; uploading the same statement twice records nothing twice")
    @PostMapping(value = "/{id}/statement", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('PAYMENT_RECORD')")
    public StatementImportResult importStatement(@PathVariable UUID id, @RequestPart("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "false") boolean dryRun) {
        return bankPayments.importStatement(id, file, dryRun);
    }
}
