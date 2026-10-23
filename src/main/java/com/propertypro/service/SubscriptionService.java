package com.propertypro.service;

import com.propertypro.dto.SubscriptionDtos.PaymentView;
import com.propertypro.dto.SubscriptionDtos.SubscriptionView;
import com.propertypro.dto.SubscriptionDtos.UpgradeRequest;
import com.propertypro.dto.SubscriptionDtos.UpgradeResponse;

import java.util.Map;
import java.util.UUID;

/** The organisation's own subscription: view it, pay for a plan by KCB STK Push, and the payment callback. */
public interface SubscriptionService {

    SubscriptionView current();

    /** Sends the payment prompt; the plan changes only when KCB confirms the payment. */
    UpgradeResponse upgrade(UpgradeRequest req);

    PaymentView payment(UUID id);

    /** KCB's STK result. Unknown tokens and repeated callbacks are ignored. */
    void stkCallback(String token, Map<String, Object> body);
}
