package com.propertypro.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import com.propertypro.dto.SubscriptionDtos.PaymentView;
import com.propertypro.dto.SubscriptionDtos.SubscriptionView;
import com.propertypro.dto.SubscriptionDtos.UpgradeRequest;
import com.propertypro.dto.SubscriptionDtos.UpgradeResponse;
import com.propertypro.entity.Plan;
import com.propertypro.entity.Subscription;
import com.propertypro.integration.KcbStkClient;
import com.propertypro.repository.PlanRepository;
import com.propertypro.repository.SubscriptionRepository;
import com.propertypro.tenancy.SystemExecutor;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class SubscriptionServiceImpl implements SubscriptionService {

    private static final DateTimeFormatter MPESA_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final SubscriptionRepository subscriptions;
    private final PlanRepository plans;
    private final KcbStkClient kcb;
    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final AppProperties props;
    private final ObjectMapper json;

    @Override
    @Transactional(readOnly = true)
    public SubscriptionView current() {
        Subscription sub = subscriptions.findFirstByOrganizationIdOrderByCreatedAtDesc(TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Subscription"));
        Plan plan = sub.getPlan();
        boolean writable = Subscription.LIVE.contains(sub.getStatus()) && sub.allowsWrites(OffsetDateTime.now());
        return new SubscriptionView(plan.getCode(), plan.getName(), sub.getStatus().name(), sub.getBillingCycle().name(),
                sub.getPrice(), plan.getCurrency(), sub.getCurrentPeriodStart(), sub.getCurrentPeriodEnd(),
                sub.getTrialEndsAt(), writable);
    }

    /** Works on an expired trial too: paying is how a read-only organisation gets going again. */
    @Override
    @Transactional
    public UpgradeResponse upgrade(UpgradeRequest req) {
        UUID orgId = TenantContext.requireOrganizationId();
        Plan plan = plans.findByCode(req.planCode().trim().toUpperCase(Locale.ROOT))
                .filter(p -> p.isActive() && p.isPublic())
                .orElseThrow(() -> ApiException.notFound("Plan"));
        BigDecimal price = req.billingCycle() == Subscription.BillingCycle.ANNUAL ? plan.getAnnualPrice() : plan.getMonthlyPrice();
        if (price == null || price.signum() <= 0) {
            throw ApiException.badRequest("PLAN_NOT_SELF_SERVE", "Contact us to subscribe to the " + plan.getName() + " plan");
        }
        Integer testAmount = kcb.testAmount();
        BigDecimal amount = testAmount != null ? BigDecimal.valueOf(testAmount) : price.setScale(0, RoundingMode.CEILING);

        boolean promptOpen = jdbc.sql("""
                        SELECT count(*) > 0 FROM subscription_payments
                         WHERE organization_id = ? AND status = 'PENDING' AND checkout_request_id IS NOT NULL
                           AND created_at > now() - interval '2 minutes'""")
                .param(orgId).query(Boolean.class).single();
        if (promptOpen) {
            throw ApiException.conflict("PAYMENT_IN_PROGRESS",
                    "A payment prompt was just sent. Complete it on your phone or wait two minutes to try again.");
        }

        String phone = req.phone().replace("+", "");
        UUID paymentId = jdbc.sql("""
                        INSERT INTO subscription_payments (organization_id, amount, method, status, plan_id, billing_cycle,
                               phone, requested_by)
                        VALUES (?, ?, 'MPESA', 'PENDING', ?, ?, ?, ?)
                        RETURNING id""")
                .param(orgId).param(amount).param(plan.getId()).param(req.billingCycle().name()).param(phone)
                .param(TenantContext.userId().orElse(null))
                .query(UUID.class).single();

        String ref = "PP" + paymentId.toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        String callbackUrl = props.web().publicApiUrl().replaceAll("/+$", "") + "/api/v1/webhooks/kcb/stk/" + kcb.callbackToken();
        KcbStkClient.StkResult result = kcb.stkPush(phone, amount.longValueExact(), kcb.invoiceNumber(ref),
                "PropertyPro " + plan.getName(), callbackUrl);

        jdbc.sql("UPDATE subscription_payments SET checkout_request_id = ?, merchant_request_id = ? WHERE id = ?")
                .param(result.checkoutRequestId()).param(result.merchantRequestId()).param(paymentId).update();
        log.info("Subscription STK {} sent: {} {} KES {} ({})", result.checkoutRequestId(), plan.getCode(),
                req.billingCycle(), amount, kcb.environment());
        return new UpgradeResponse(paymentId, result.checkoutRequestId(), amount, result.customerMessage());
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView payment(UUID id) {
        return jdbc.sql("""
                        SELECT sp.id, sp.status, p.code, sp.billing_cycle, sp.amount, sp.phone, sp.reference,
                               sp.result_code, sp.result_desc, sp.paid_at, sp.created_at
                          FROM subscription_payments sp
                          LEFT JOIN plans p ON p.id = sp.plan_id
                         WHERE sp.id = ? AND sp.organization_id = ?""")
                .param(id).param(TenantContext.requireOrganizationId())
                .query((rs, n) -> new PaymentView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getBigDecimal(5), rs.getString(6), rs.getString(7),
                        (Integer) rs.getObject(8), rs.getString(9), rs.getObject(10, OffsetDateTime.class),
                        rs.getObject(11, OffsetDateTime.class)))
                .optional().orElseThrow(() -> ApiException.notFound("Payment"));
    }

    // -------------------------------------------------------------- callback

    private record Pending(UUID id, UUID organizationId, UUID planId, Subscription.BillingCycle cycle, BigDecimal amount) {}

    @Override
    @SuppressWarnings("unchecked")
    public void stkCallback(String token, Map<String, Object> body) {
        if (!kcb.configured() || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                kcb.callbackToken().getBytes(StandardCharsets.UTF_8))) {
            log.warn("KCB STK callback with unknown token; ignored");
            return;
        }
        Map<String, Object> cb = (Map<String, Object>) ((Map<String, Object>) body.getOrDefault("Body", Map.of()))
                .getOrDefault("stkCallback", Map.of());
        String checkoutId = str(cb.get("CheckoutRequestID"));
        int resultCode = resultCode(cb.get("ResultCode"));
        String resultDesc = str(cb.get("ResultDesc"));

        String receipt = null;
        BigDecimal paid = null;
        OffsetDateTime time = OffsetDateTime.now();
        if (cb.get("CallbackMetadata") instanceof Map<?, ?> m && m.get("Item") instanceof List<?> items) {
            for (Object o : items) {
                if (!(o instanceof Map<?, ?> item) || item.get("Value") == null) continue;
                String value = str(item.get("Value"));
                switch (str(item.get("Name"))) {
                    case "MpesaReceiptNumber" -> receipt = value;
                    case "Amount" -> paid = new BigDecimal(value);
                    case "TransactionDate" -> time = parseTime(value);
                    default -> { }
                }
            }
        }

        Optional<Pending> found = system.asSystem(() -> jdbc.sql("""
                        SELECT id, organization_id, plan_id, billing_cycle, amount FROM subscription_payments
                         WHERE checkout_request_id = ? AND status = 'PENDING'""")
                .param(checkoutId)
                .query((rs, n) -> new Pending(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getObject(3, UUID.class), Subscription.BillingCycle.valueOf(rs.getString(4)), rs.getBigDecimal(5)))
                .optional());
        if (found.isEmpty()) {
            log.info("KCB STK {} (code {}) has no pending subscription payment; ignored", checkoutId, resultCode);
            return;
        }
        Pending p = found.get();
        String raw = toJson(body);

        if (resultCode != 0 || receipt == null || receipt.isEmpty()) {
            fail(p, resultCode, resultDesc, raw);
            log.info("Subscription STK {} not paid: {} ({})", checkoutId, resultCode, resultDesc);
            return;
        }
        if (paid == null || paid.compareTo(p.amount()) < 0) {
            fail(p, resultCode, "Paid " + paid + ", expected " + p.amount() + " (receipt " + receipt + ")", raw);
            log.error("Subscription STK {} paid {} but {} was due; plan NOT changed, refund or apply manually",
                    checkoutId, paid, p.amount());
            return;
        }

        final String fReceipt = receipt;
        final OffsetDateTime fTime = time;
        system.asOrganization(p.organizationId(), null, () -> {
            int updated = jdbc.sql("""
                            UPDATE subscription_payments
                               SET status = 'SUCCESS', reference = ?, paid_at = ?, result_code = ?, result_desc = ?,
                                   raw_response = ?::jsonb
                             WHERE id = ? AND status = 'PENDING'""")
                    .param(fReceipt).param(fTime).param(resultCode).param(resultDesc).param(raw).param(p.id())
                    .update();
            if (updated == 1) activate(p);
            return null;
        });
        log.info("Subscription paid: org {} receipt {} KES {}", p.organizationId(), fReceipt, paid);
    }

    /**
     * Makes the paid plan the live subscription. Paying again for the same plan and cycle
     * while active extends the period; anything else starts a new period today.
     */
    private void activate(Pending p) {
        Plan plan = plans.findById(p.planId()).orElseThrow(() -> ApiException.notFound("Plan"));
        LocalDate today = LocalDate.now(NAIROBI);
        Subscription sub = subscriptions.findFirstByOrganizationIdAndStatusIn(p.organizationId(), Subscription.LIVE)
                .orElseGet(() -> {
                    Subscription s = new Subscription();
                    s.setOrganizationId(p.organizationId());
                    return s;
                });
        boolean renewal = sub.getStatus() == Subscription.Status.ACTIVE && sub.getPlan() != null
                && sub.getPlan().getId().equals(plan.getId()) && sub.getBillingCycle() == p.cycle()
                && sub.getCurrentPeriodEnd() != null && !sub.getCurrentPeriodEnd().isBefore(today);
        LocalDate from = renewal ? sub.getCurrentPeriodEnd() : today;
        if (!renewal) sub.setCurrentPeriodStart(today);
        sub.setCurrentPeriodEnd(p.cycle() == Subscription.BillingCycle.ANNUAL ? from.plusYears(1) : from.plusMonths(1));
        sub.setPlan(plan);
        sub.setStatus(Subscription.Status.ACTIVE);
        sub.setBillingCycle(p.cycle());
        sub.setPrice(p.cycle() == Subscription.BillingCycle.ANNUAL ? plan.getAnnualPrice() : plan.getMonthlyPrice());
        sub.setTrialEndsAt(null);
        sub.setGraceEndsAt(null);
        sub.setCancelAtPeriodEnd(false);
        subscriptions.save(sub);
        // A payment never lifts an admin suspension; reactivation is a deliberate admin action
        jdbc.sql("UPDATE organizations SET status = 'ACTIVE' WHERE id = ? AND status <> 'SUSPENDED'")
                .param(p.organizationId()).update();
    }

    private void fail(Pending p, int resultCode, String resultDesc, String raw) {
        system.asSystem(() -> jdbc.sql("""
                        UPDATE subscription_payments
                           SET status = 'FAILED', result_code = ?, result_desc = ?, raw_response = ?::jsonb
                         WHERE id = ? AND status = 'PENDING'""")
                .param(resultCode).param(resultDesc).param(raw).param(p.id()).update());
    }

    // ------------------------------------------------------------------ helpers

    /** KCB sends ResultCode as a number; tolerate a string. */
    private static int resultCode(Object value) {
        if (value instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(str(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static OffsetDateTime parseTime(String value) {
        try {
            return LocalDateTime.parse(value, MPESA_TIME).atZone(NAIROBI).toOffsetDateTime();
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
