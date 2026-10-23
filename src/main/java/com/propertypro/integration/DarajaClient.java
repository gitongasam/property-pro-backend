package com.propertypro.integration;

import com.propertypro.common.ApiException;
import com.propertypro.entity.PaymentChannel;
import com.propertypro.security.CryptoService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Safaricom Daraja API calls made with one organisation's own credentials:
 * OAuth token, C2B URL registration and STK Push. Payments themselves arrive on
 * the webhooks ({@link MpesaWebhookController}), not through this client.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DarajaClient {

    private static final DateTimeFormatter STK_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final CryptoService crypto;
    private final RestClient http = RestClient.create();

    public record StkResult(String merchantRequestId, String checkoutRequestId, String customerMessage) {}

    private static String baseUrl(PaymentChannel channel) {
        return channel.getEnvironment() == PaymentChannel.Environment.PRODUCTION
                ? "https://api.safaricom.co.ke" : "https://sandbox.safaricom.co.ke";
    }

    @SuppressWarnings("unchecked")
    String accessToken(PaymentChannel channel) {
        String key = crypto.decrypt(channel.getConsumerKeyEnc());
        String secret = crypto.decrypt(channel.getConsumerSecretEnc());
        if (key == null || secret == null) {
            throw ApiException.badRequest("DARAJA_KEYS_MISSING", "Add the Daraja consumer key and secret first");
        }
        String basic = Base64.getEncoder().encodeToString((key + ":" + secret).getBytes(StandardCharsets.UTF_8));
        try {
            Map<String, Object> body = http.get()
                    .uri(baseUrl(channel) + "/oauth/v1/generate?grant_type=client_credentials")
                    .header("Authorization", "Basic " + basic)
                    .retrieve().body(Map.class);
            Object token = body == null ? null : body.get("access_token");
            if (token == null) throw new RestClientException("no access_token in response");
            return token.toString();
        } catch (RestClientException e) {
            log.warn("Daraja OAuth failed for shortcode {}: {}", channel.getShortcode(), e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DARAJA_AUTH_FAILED",
                    "Safaricom rejected the Daraja keys. Check the consumer key, secret and environment.");
        }
    }

    /** Tells Safaricom where to send C2B validation and confirmation callbacks for this shortcode. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> registerC2bUrls(PaymentChannel channel, String validationUrl, String confirmationUrl) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ShortCode", channel.getShortcode());
        request.put("ResponseType", "Completed");     // accept the payment if our validation URL is unreachable
        request.put("ConfirmationURL", confirmationUrl);
        request.put("ValidationURL", validationUrl);
        try {
            return http.post()
                    .uri(baseUrl(channel) + "/mpesa/c2b/v1/registerurl")
                    .header("Authorization", "Bearer " + accessToken(channel))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(Map.class);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "DARAJA_REGISTER_FAILED",
                    "Safaricom did not accept the callback URLs: " + e.getMessage());
        }
    }

    /** Sends a "Pay KES x" prompt to the customer's phone. */
    @SuppressWarnings("unchecked")
    public StkResult stkPush(PaymentChannel channel, String phone254, long amount, String accountReference,
                             String description, String callbackUrl) {
        String passkey = crypto.decrypt(channel.getPasskeyEnc());
        if (passkey == null) {
            throw ApiException.badRequest("STK_PASSKEY_MISSING", "Add the STK Push passkey for this Paybill first");
        }
        String timestamp = LocalDateTime.now(ZoneId.of("Africa/Nairobi")).format(STK_TIME);
        String password = Base64.getEncoder().encodeToString(
                (channel.getShortcode() + passkey + timestamp).getBytes(StandardCharsets.UTF_8));

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("BusinessShortCode", channel.getShortcode());
        request.put("Password", password);
        request.put("Timestamp", timestamp);
        request.put("TransactionType", channel.getChannelType() == PaymentChannel.Type.MPESA_TILL
                ? "CustomerBuyGoodsOnline" : "CustomerPayBillOnline");
        request.put("Amount", amount);
        request.put("PartyA", phone254);
        request.put("PartyB", channel.getShortcode());
        request.put("PhoneNumber", phone254);
        request.put("CallBackURL", callbackUrl);
        request.put("AccountReference", accountReference.length() > 12 ? accountReference.substring(0, 12) : accountReference);
        request.put("TransactionDesc", description.length() > 13 ? description.substring(0, 13) : description);
        try {
            Map<String, Object> res = http.post()
                    .uri(baseUrl(channel) + "/mpesa/stkpush/v1/processrequest")
                    .header("Authorization", "Bearer " + accessToken(channel))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(request)
                    .retrieve().body(Map.class);
            if (res == null || !"0".equals(String.valueOf(res.get("ResponseCode")))) {
                throw new RestClientException(res == null ? "empty response" : String.valueOf(res.get("ResponseDescription")));
            }
            return new StkResult(String.valueOf(res.get("MerchantRequestID")), String.valueOf(res.get("CheckoutRequestID")),
                    String.valueOf(res.get("CustomerMessage")));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "STK_FAILED", "M-Pesa could not send the prompt: " + e.getMessage());
        }
    }
}
