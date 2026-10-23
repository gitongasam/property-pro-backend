package com.propertypro.integration;

import com.propertypro.config.AppProperties;
import com.propertypro.dto.BankDtos.BankCredit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;

/**
 * KCB Buni Instant Payment Notifications (Paybill 522522). The "Signature"
 * header is a base64 SHA256withRSA signature of the raw body made with KCB's
 * private key. Without a configured public key every notification is refused,
 * unless verification is switched off for local testing.
 */
@Slf4j
@Component
public class KcbNotificationAdapter implements BankNotificationAdapter {

    private static final DateTimeFormatter COMPACT_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final PublicKey publicKey;
    private final boolean verifySignature;

    public KcbNotificationAdapter(AppProperties props) {
        AppProperties.Kcb kcb = props.kcb();
        this.verifySignature = kcb == null || kcb.verifySignature();
        this.publicKey = kcb == null || kcb.ipnPublicKey() == null || kcb.ipnPublicKey().isBlank()
                ? null : parseKey(kcb.ipnPublicKey());
        if (!verifySignature) {
            log.warn("KCB notification signatures are NOT verified (app.kcb.verify-signature=false). Never use this in production");
        } else if (publicKey == null) {
            log.info("app.kcb.ipn-public-key is not set; KCB payment notifications will be refused");
        }
    }

    @Override
    public String bankCode() {
        return "KCB";
    }

    @Override
    public boolean verify(String rawBody, HttpHeaders headers) {
        if (!verifySignature) {
            return true;
        }
        String signature = headers.getFirst("Signature");
        if (publicKey == null || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            Signature rsa = Signature.getInstance("SHA256withRSA");
            rsa.initVerify(publicKey);
            rsa.update(rawBody.getBytes(StandardCharsets.UTF_8));
            return rsa.verify(Base64.getDecoder().decode(signature.trim()));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public BankCredit parse(Map<String, Object> body) {
        return new BankCredit(
                str(body.get("transactionReference")),
                str(body.get("requestId")),
                str(body.get("channelCode")),
                parseTime(str(body.get("timestamp"))),
                amount(str(body.get("transactionAmount"))),
                str(body.get("currency")),
                str(body.get("customerReference")),
                str(body.get("customerName")),
                str(body.get("customerMobileNumber")),
                str(body.get("narration")),
                str(body.get("creditAccountIdentifier")),
                amount(str(body.get("balance"))));
    }

    @Override
    public Object acknowledgement(String reference, boolean accepted, String message) {
        return Map.of(
                "transactionID", reference == null ? "" : reference,
                "statusCode", accepted ? 0 : 1,
                "statusMessage", message);
    }

    /** Accepts the key as PEM ("-----BEGIN PUBLIC KEY-----...") or as bare base64. */
    private static PublicKey parseKey(String key) {
        String base64 = key.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s+", "");
        try {
            return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("app.kcb.ipn-public-key is not a valid RSA public key", e);
        }
    }

    private static BigDecimal amount(String value) {
        try {
            return value.isEmpty() ? null : new BigDecimal(value.replace(",", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** ISO-8601 or yyyyMMddHHmmss (Nairobi time); anything else falls back to now. */
    private static OffsetDateTime parseTime(String value) {
        try {
            return OffsetDateTime.parse(value);
        } catch (RuntimeException ignored) {
            // not ISO with an offset
        }
        try {
            return LocalDateTime.parse(value).atZone(NAIROBI).toOffsetDateTime();
        } catch (RuntimeException ignored) {
            // not ISO without an offset
        }
        try {
            return LocalDateTime.parse(value, COMPACT_TIME).atZone(NAIROBI).toOffsetDateTime();
        } catch (RuntimeException e) {
            return OffsetDateTime.now();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }
}
