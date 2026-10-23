package com.propertypro.controller;

import com.propertypro.dto.BankDtos.BankAck;
import com.propertypro.service.BankPaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Called by banks (KCB Buni IPN today), not by users, when money lands in a
 * landlord's account. Public; the token in the path identifies the channel and
 * the bank's connector checks the signature.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/bank/{token}")
@RequiredArgsConstructor
public class BankWebhookController {

    private final BankPaymentService bankPayments;

    /** The body is read as text so the signature is checked against exactly what the bank signed. */
    @PostMapping("/notification")
    public ResponseEntity<Object> notification(@PathVariable String token, @RequestHeader HttpHeaders headers,
                                               @RequestBody String raw) {
        BankAck ack = bankPayments.notification(token, raw, headers);
        return ResponseEntity.status(ack.httpStatus()).body(ack.body());
    }
}
