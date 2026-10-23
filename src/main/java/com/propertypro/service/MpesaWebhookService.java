package com.propertypro.service;

import com.propertypro.dto.PaymentDtos.ChannelRef;

import java.util.Map;
import java.util.Optional;

/**
 * Handles Safaricom callbacks. The channel is found from the secret token in
 * the URL (so the organisation is known before anything is read); the raw
 * callback is stored first, a duplicate receipt is ignored, then the payment is
 * created inside that organisation's context and matched.
 *
 * <p>Safaricom must always get a 200 with ResultCode 0, otherwise it retries;
 * problems are logged and the transaction is kept for staff to handle.
 */
public interface MpesaWebhookService {

    Optional<ChannelRef> channel(String token);

    void c2bConfirmation(String token, Map<String, Object> body);

    void stkCallback(String token, Map<String, Object> body);
}
