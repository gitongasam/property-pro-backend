package com.propertypro.service.messaging;

public interface SmsProvider {
    void sendSms(String phone, String message);
}
