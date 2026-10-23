package com.propertypro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.dto.PaymentDtos.ChannelRef;
import com.propertypro.entity.Payment;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class MpesaWebhookServiceImpl implements MpesaWebhookService {

    private static final DateTimeFormatter DARAJA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final PaymentService payments;
    private final ObjectMapper json;

    @Override
    public Optional<ChannelRef> channel(String token) {
        return system.asSystem(() -> jdbc.sql("""
                        SELECT id, organization_id, shortcode FROM payment_channels
                         WHERE webhook_token = ? AND active""")
                .param(token)
                .query((rs, n) -> new ChannelRef(rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                        rs.getString("shortcode")))
                .optional());
    }

    @Override
    // ------------------------------------------------------------------- C2B

    public void c2bConfirmation(String token, Map<String, Object> body) {
        Optional<ChannelRef> ch = channel(token);
        if (ch.isEmpty()) {
            log.warn("M-Pesa confirmation with unknown token; ignored");
            return;
        }
        ChannelRef c = ch.get();
        String transId = str(body.get("TransID"));
        BigDecimal amount = new BigDecimal(str(body.getOrDefault("TransAmount", "0")));
        OffsetDateTime time = parseTime(str(body.get("TransTime")));
        String billRef = str(body.get("BillRefNumber"));
        String phone = str(body.get("MSISDN"));
        String payer = String.join(" ", List.of(str(body.get("FirstName")), str(body.get("MiddleName")), str(body.get("LastName"))))
                .trim().replaceAll("\\s+", " ");

        UUID txId = system.asSystem(() -> jdbc.sql("""
                        INSERT INTO mpesa_transactions (organization_id, payment_channel_id, source, trans_id, trans_type,
                               trans_time, amount, business_shortcode, bill_ref_number, msisdn, payer_name, raw_payload)
                        VALUES (?, ?, 'C2B', ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                        ON CONFLICT (trans_id) WHERE trans_id IS NOT NULL DO NOTHING
                        RETURNING id""")
                .param(c.organizationId()).param(c.id()).param(transId).param(str(body.get("TransactionType")))
                .param(time).param(amount).param(str(body.get("BusinessShortCode"))).param(billRef).param(phone)
                .param(payer).param(toJson(body))
                .query(UUID.class).optional().orElse(null));
        if (txId == null) {
            log.info("M-Pesa {} already received; duplicate callback ignored", transId);
            return;
        }

        try {
            Payment p = system.asOrganization(c.organizationId(), null, () -> {
                Payment created = payments.recordMpesa(c.id(), Payment.Source.MPESA_C2B, transId, amount, time,
                        payer, phone, billRef, null);
                markProcessed(txId, created);
                return created;
            });
            log.info("M-Pesa {} KES {} account '{}' -> {}", transId, amount, billRef, p.getStatus());
        } catch (RuntimeException e) {
            log.error("Processing M-Pesa {} failed; kept for review", transId, e);
            system.asSystem(() -> jdbc.sql("UPDATE mpesa_transactions SET processing_status = 'ERROR', result_desc = ? WHERE id = ?")
                    .param(String.valueOf(e.getMessage())).param(txId).update());
        }
    }

    @Override
    // ------------------------------------------------------------------- STK

    /** Result of an STK Push we started; the tenant was recorded when the prompt was sent. */
    @SuppressWarnings("unchecked")
    public void stkCallback(String token, Map<String, Object> body) {
        Optional<ChannelRef> ch = channel(token);
        if (ch.isEmpty()) return;
        ChannelRef c = ch.get();
        Map<String, Object> cb = (Map<String, Object>) ((Map<String, Object>) body.getOrDefault("Body", Map.of()))
                .getOrDefault("stkCallback", Map.of());
        String checkoutId = str(cb.get("CheckoutRequestID"));
        int resultCode = cb.get("ResultCode") instanceof Number n ? n.intValue() : -1;
        String resultDesc = str(cb.get("ResultDesc"));

        String receipt = null;
        BigDecimal amount = null;
        String phone = null;
        OffsetDateTime time = OffsetDateTime.now();
        Object meta = cb.get("CallbackMetadata");
        if (meta instanceof Map<?, ?> m && m.get("Item") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> item)) continue;
                String name = str(item.get("Name"));
                Object value = item.get("Value");
                switch (name) {
                    case "MpesaReceiptNumber" -> receipt = str(value);
                    case "Amount" -> amount = new BigDecimal(str(value));
                    case "PhoneNumber" -> phone = str(value);
                    case "TransactionDate" -> time = parseTime(str(value));
                    default -> { }
                }
            }
        }
        final String fReceipt = receipt;
        final BigDecimal fAmount = amount;
        final String fPhone = phone;
        final OffsetDateTime fTime = time;

        record Pending(UUID id, UUID tenantId, String billRef) {}
        Optional<Pending> pending = system.asSystem(() -> jdbc.sql("""
                        UPDATE mpesa_transactions
                           SET result_code = ?, result_desc = ?, trans_id = ?, amount = coalesce(?, amount),
                               msisdn = coalesce(?, msisdn), trans_time = ?, raw_payload = raw_payload || ?::jsonb,
                               processing_status = CASE WHEN ? = 0 THEN processing_status ELSE 'FAILED' END
                         WHERE checkout_request_id = ? AND payment_id IS NULL
                        RETURNING id, (raw_payload ->> 'tenantId')::uuid AS tenant_id, bill_ref_number""")
                .param(resultCode).param(resultDesc).param(fReceipt).param(fAmount).param(fPhone).param(fTime)
                .param(toJson(Map.of("callback", body))).param(resultCode).param(checkoutId)
                .query((rs, n) -> new Pending(rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                        rs.getString("bill_ref_number")))
                .optional());
        if (pending.isEmpty() || resultCode != 0 || fReceipt == null || fAmount == null) {
            log.info("STK {} finished with code {} ({})", checkoutId, resultCode, resultDesc);
            return;
        }
        Pending pend = pending.get();
        system.asOrganization(c.organizationId(), null, () -> {
            Payment created = payments.recordMpesa(c.id(), Payment.Source.MPESA_STK, fReceipt, fAmount, fTime,
                    null, fPhone, pend.billRef(), pend.tenantId());
            markProcessed(pend.id(), created);
            return null;
        });
    }

    // ------------------------------------------------------------------ helpers

    private void markProcessed(UUID txId, Payment p) {
        jdbc.sql("""
                        UPDATE mpesa_transactions SET payment_id = ?, processed_at = now(),
                               processing_status = CASE WHEN ? = 'UNMATCHED' THEN 'UNMATCHED' ELSE 'MATCHED' END
                         WHERE id = ?""")
                .param(p.getId()).param(p.getStatus().name()).param(txId).update();
        jdbc.sql("UPDATE payment_channels SET verified_at = now() WHERE id = ? AND verified_at IS NULL")
                .param(p.getPaymentChannelId()).update();
    }

    private static OffsetDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value, DARAJA_TIME).atZone(NAIROBI).toOffsetDateTime();
        } catch (RuntimeException e) {
            return OffsetDateTime.now();
        }
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private String toJson(Object o) {
        try {
            return json.writeValueAsString(o);
        } catch (Exception e) {
            return "{}";
        }
    }
}
