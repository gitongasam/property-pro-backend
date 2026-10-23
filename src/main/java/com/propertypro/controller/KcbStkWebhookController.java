package com.propertypro.controller;

import com.propertypro.service.SubscriptionService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Called by KCB Buni, not by users, with the result of a subscription STK Push.
 * Public; the secret token in the path (app.kcb-stk.callback-token) is the only check,
 * as KCB does not sign STK callbacks.
 */
@Hidden
@RestController
@RequestMapping("/api/v1/webhooks/kcb/stk/{token}")
@RequiredArgsConstructor
public class KcbStkWebhookController {

    private static final Map<String, Object> ACCEPTED = Map.of("ResultCode", 0, "ResultDesc", "Accepted");

    private final SubscriptionService subscriptions;

    @PostMapping
    public Map<String, Object> callback(@PathVariable String token, @RequestBody Map<String, Object> body) {
        subscriptions.stkCallback(token, body);
        return ACCEPTED;
    }
}
