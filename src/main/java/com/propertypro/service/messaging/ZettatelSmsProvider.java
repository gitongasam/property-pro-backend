package com.propertypro.service.messaging;

import com.propertypro.integration.MessageGateways.SmsGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends SMS through Zettatel. Active only when zettatel.user-id and zettatel.password
 * are set; it then replaces the logging gateway, so every queued SMS (invoices,
 * receipts, reminders, leases) and every confirmation code goes out through it.
 */
@Service
@ConditionalOnExpression("'${zettatel.user-id:}' != '' and '${zettatel.password:}' != ''")
public class ZettatelSmsProvider implements SmsProvider, SmsGateway {

    private static final Logger log = LoggerFactory.getLogger(ZettatelSmsProvider.class);
    private static final String URL = "https://portal.zettatel.com/SMSApi/send";

    private final String userId;
    private final String password;
    private final String senderId;
    private final RestClient http = RestClient.create();

    public ZettatelSmsProvider(@Value("${zettatel.user-id}") String userId,
                               @Value("${zettatel.password}") String password,
                               @Value("${zettatel.sender-id:}") String senderId) {
        this.userId = userId;
        this.password = password;
        this.senderId = senderId;
        log.info("SMS goes out through Zettatel (sender id '{}')", senderId);
    }

    @Override
    public void sendSms(String phone, String message) {
        send(phone, message);
    }

    /** Throws on failure so the notification queue marks the message FAILED and retries it. */
    @Override
    @SuppressWarnings("unchecked")
    public String send(String toPhone, String text) {
        String mobile = normalizeKenyanPhone(toPhone);
        if (mobile == null) {
            throw new IllegalArgumentException("Not a Kenyan mobile number: " + toPhone);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userid", userId);
        body.put("password", password);
        body.put("senderid", senderId);
        body.put("msgType", "text");
        body.put("duplicatecheck", "true");
        body.put("sendMethod", "quick");
        body.put("sms", List.of(Map.of("mobile", List.of(mobile), "msg", text)));
        try {
            Map<String, Object> res = http.post().uri(URL)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve().body(Map.class);
            String status = res == null ? "" : String.valueOf(res.getOrDefault("status", ""));
            if (res == null || "error".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Zettatel refused the SMS: " + res);
            }
            Object id = res.get("transactionId");
            log.info("Zettatel SMS sent to {}", mask(mobile));
            return id == null ? "zettatel-" + UUID.randomUUID() : String.valueOf(id);
        } catch (RestClientResponseException e) {
            throw new IllegalStateException("Zettatel HTTP " + e.getStatusCode().value() + ": " + e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            throw new IllegalStateException("Zettatel unreachable: " + e.getMessage(), e);
        }
    }

    /** 0712345678, +254712345678 and 254712345678 all become 254712345678; anything else is null. */
    static String normalizeKenyanPhone(String phone) {
        if (phone == null) return null;
        String digits = phone.replaceAll("[\\s-]", "").replaceFirst("^\\+", "");
        if (digits.matches("0[17]\\d{8}")) return "254" + digits.substring(1);
        if (digits.matches("254[17]\\d{8}")) return digits;
        return null;
    }

    private static String mask(String mobile) {
        return mobile.substring(0, 6) + "***" + mobile.substring(mobile.length() - 3);
    }
}
