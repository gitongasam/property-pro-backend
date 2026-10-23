package com.propertypro.integration;

import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * KCB Buni M-Pesa STK Push with PropertyPro's own credentials, used to collect
 * subscription payments. The result arrives on the callback
 * ({@code KcbStkWebhookController}), not through this client.
 */
@Slf4j
@Component
public class KcbStkClient {

    private static final String UAT_URL = "https://uat.buni.kcbgroup.com";
    private static final String PRODUCTION_URL = "https://api.buni.kcbgroup.com";

    private final AppProperties.KcbStk config;
    private final RestClient http = RestClient.create();

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public record StkResult(String merchantRequestId, String checkoutRequestId, String customerMessage) {}

    public KcbStkClient(AppProperties props) {
        this.config = props.kcbStk();
        if (configured()) {
            log.info("KCB STK Push for subscriptions: {} ({})", environment(), baseUrl());
        } else {
            log.warn("KCB STK Push for subscriptions is off; missing: {}", String.join(", ", missing()));
        }
    }

    private List<String> missing() {
        List<String> missing = new ArrayList<>();
        if (config == null || !notBlank(config.consumerKey())) missing.add("KCB_STK_CONSUMER_KEY");
        if (config == null || !notBlank(config.consumerSecret())) missing.add("KCB_STK_CONSUMER_SECRET");
        if (config == null || !notBlank(config.callbackToken())) missing.add("KCB_STK_CALLBACK_TOKEN");
        return missing;
    }

    public boolean configured() {
        return config != null && notBlank(config.consumerKey()) && notBlank(config.consumerSecret())
                && notBlank(config.callbackToken());
    }

    public AppProperties.KcbStk.Environment environment() {
        return config == null || config.environment() == null ? AppProperties.KcbStk.Environment.UAT : config.environment();
    }

    public boolean production() {
        return environment() == AppProperties.KcbStk.Environment.PRODUCTION;
    }

    /** Shillings to charge in UAT instead of the plan price; {@code null} in production or when unset. */
    public Integer testAmount() {
        return production() || config == null || config.testAmount() == null || config.testAmount() < 1
                ? null : config.testAmount();
    }

    public String callbackToken() {
        return config == null ? null : config.callbackToken();
    }

    /** "<prefix>-<ref>" when an invoice prefix (KCB account or Till number) is set, else the ref alone. */
    public String invoiceNumber(String ref) {
        return config != null && notBlank(config.invoicePrefix()) ? config.invoicePrefix().trim() + "-" + ref : ref;
    }

    private String baseUrl() {
        if (config != null && notBlank(config.baseUrl())) return config.baseUrl().replaceAll("/+$", "");
        return production() ? PRODUCTION_URL : UAT_URL;
    }

    /** Tokens last an hour; one is reused until a minute before it expires. */
    @SuppressWarnings("unchecked")
    synchronized String accessToken() {
        if (token != null && Instant.now().isBefore(tokenExpiresAt)) return token;
        String basic = Base64.getEncoder().encodeToString(
                (config.consumerKey() + ":" + config.consumerSecret()).getBytes(StandardCharsets.UTF_8));
        try {
            Map<String, Object> body = http.post()
                    .uri(baseUrl() + "/token?grant_type=client_credentials")
                    .header("Authorization", "Basic " + basic)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .retrieve().body(Map.class);
            Object accessToken = body == null ? null : body.get("access_token");
            if (accessToken == null) throw new RestClientException("no access_token in response");
            long expiresIn = body.get("expires_in") instanceof Number n ? n.longValue() : 3600;
            token = accessToken.toString();
            tokenExpiresAt = Instant.now().plusSeconds(Math.max(expiresIn - 60, 0));
            return token;
        } catch (RestClientException e) {
            log.warn("KCB Buni token request failed ({}): {}", environment(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "KCB_AUTH_FAILED",
                    "Payments are unavailable right now. Please try again shortly.");
        }
    }

    /** Sends a "Pay KES x" prompt to the customer's phone (2547XXXXXXXX). */
    @SuppressWarnings("unchecked")
    public StkResult stkPush(String phone254, long amount, String invoiceNumber, String description, String callbackUrl) {
        if (!configured()) {
            log.warn("STK Push refused: KCB is not configured; missing: {}", String.join(", ", missing()));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "PAYMENTS_NOT_CONFIGURED",
                    "Online payment is not set up yet. Contact support to upgrade.");
        }
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("phoneNumber", phone254);
        request.put("amount", String.valueOf(amount));
        request.put("invoiceNumber", invoiceNumber);
        request.put("sharedShortCode", config.sharedShortCode());
        request.put("orgShortCode", config.orgShortCode() == null ? "" : config.orgShortCode());
        request.put("orgPassKey", config.orgPassKey() == null ? "" : config.orgPassKey());
        request.put("callbackUrl", callbackUrl);
        request.put("transactionDescription", description);
        try {
            Map<String, Object> res = http.post()
                    .uri(baseUrl() + "/mm/api/request/1.0.0/stkpush")
                    .header("Authorization", "Bearer " + accessToken())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(Map.class);
            Map<String, Object> body = res == null ? Map.of() : (Map<String, Object>) res.getOrDefault("response", Map.of());
            Map<String, Object> header = res == null ? Map.of() : (Map<String, Object>) res.getOrDefault("header", Map.of());
            if (!"0".equals(String.valueOf(body.get("ResponseCode")))) {
                String reason = header.get("statusDescription") != null ? String.valueOf(header.get("statusDescription"))
                        : res == null ? "empty response" : String.valueOf(res);
                throw new RestClientException(reason);
            }
            return new StkResult(String.valueOf(body.get("MerchantRequestID")), String.valueOf(body.get("CheckoutRequestID")),
                    String.valueOf(body.get("CustomerMessage")));
        } catch (RestClientException e) {
            if (e instanceof RestClientResponseException r && r.getStatusCode().value() == 401) {
                synchronized (this) {
                    token = null;                       // revoked or expired early; fetch a new one next time
                }
            }
            String detail = e instanceof RestClientResponseException r ? r.getResponseBodyAsString() : e.getMessage();
            log.warn("KCB STK Push to {} failed ({}): {}", phone254, environment(), detail);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STK_FAILED",
                    "M-Pesa could not send the payment prompt: " + e.getMessage());
        }
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
