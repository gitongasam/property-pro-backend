package com.propertypro.service;

import com.propertypro.dto.PaymentDtos.ChannelRequest;
import com.propertypro.dto.PaymentDtos.ChannelResponse;
import com.propertypro.dto.PaymentDtos.StkPushRequest;
import com.propertypro.dto.PaymentDtos.StkPushResponse;
import com.propertypro.entity.PaymentChannel;

import java.util.List;
import java.util.UUID;

/** Paybill / Till / bank Paybill set-up (Settings › Payments), callback URL registration and STK Push. */
public interface PaymentChannelService {

    List<ChannelResponse> list();

    ChannelResponse create(ChannelRequest req);

    ChannelResponse update(UUID id, ChannelRequest req);

    void deactivate(UUID id);

    /** Registers this channel's callback URLs with Safaricom. */
    ChannelResponse registerUrls(UUID id);

    /** Sends a payment prompt to the tenant's phone. The result arrives on the STK callback. */
    StkPushResponse stkPush(StkPushRequest req);

    PaymentChannel find(UUID id);
}
