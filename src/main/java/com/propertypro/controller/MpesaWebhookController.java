package com.propertypro.controller;

import com.propertypro.service.MpesaWebhookService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Called by Safaricom, not by users. Public; the token in the path identifies the channel. */
@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/mpesa/{token}")
@RequiredArgsConstructor
public class MpesaWebhookController {

    private static final Map<String, Object> ACCEPTED = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final MpesaWebhookService webhooks;

    /** Accept every payment: refusing would send the tenant's money back. Matching happens on confirmation. */
    @PostMapping("/validation")
    public Map<String, Object> validation(@PathVariable String token, @RequestBody(required = false) Map<String, Object> body) {
        return ACCEPTED;
    }

    @PostMapping("/confirmation")
    public Map<String, Object> confirmation(@PathVariable String token, @RequestBody Map<String, Object> body) {
        webhooks.c2bConfirmation(token, body);
        return ACCEPTED;
    }

    @PostMapping("/stk")
    public Map<String, Object> stk(@PathVariable String token, @RequestBody Map<String, Object> body) {
        webhooks.stkCallback(token, body);
        return ACCEPTED;
    }
}
