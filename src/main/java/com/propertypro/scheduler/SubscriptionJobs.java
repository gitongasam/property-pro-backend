package com.propertypro.scheduler;

import com.propertypro.config.AppProperties;
import com.propertypro.service.NotificationService;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The life of a paid subscription after its period ends (trials: see TrialJobs). Daily at 07:00:
 * <ul>
 *   <li>{@code reminderDays} before the end: renewal reminder to the owner (email + SMS), once.</li>
 *   <li>Period over: ACTIVE becomes PAST_DUE with {@code graceDays} of grace, still fully usable
 *       (or CANCELLED when it was set to cancel at period end).</li>
 *   <li>Grace over: the subscription stays PAST_DUE but is read-only ({@code Subscription.allowsWrites}),
 *       BillingJobs skips it, and the owner is told once.</li>
 * </ul>
 * Paying (SubscriptionServiceImpl.activate) makes it ACTIVE again with a new period.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class SubscriptionJobs {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AppProperties props;

    private record Sub(UUID id, UUID orgId, String orgName, String status, LocalDate periodEnd,
                       OffsetDateTime graceEndsAt, boolean cancelAtPeriodEnd, BigDecimal price, String planName) {}

    private record Owner(UUID id, String firstName, String email, String phone) {}

    @Scheduled(cron = "${app.jobs.subscription-cron:0 0 7 * * *}", zone = "Africa/Nairobi")
    public void run() {
        List<Sub> subs = system.asSystem(() -> jdbc.sql("""
                        SELECT s.id, s.organization_id, o.name, s.status, s.current_period_end, s.grace_ends_at,
                               s.cancel_at_period_end, s.price, p.name
                          FROM subscriptions s
                          JOIN organizations o ON o.id = s.organization_id AND o.deleted_at IS NULL
                          JOIN plans p ON p.id = s.plan_id
                         WHERE s.status IN ('ACTIVE','PAST_DUE')""")
                .query((rs, n) -> new Sub(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3),
                        rs.getString(4), rs.getObject(5, LocalDate.class), rs.getObject(6, OffsetDateTime.class),
                        rs.getBoolean(7), rs.getBigDecimal(8), rs.getString(9)))
                .list());
        int changed = 0;
        for (Sub s : subs) {
            try {
                changed += system.asOrganization(s.orgId(), null, () -> process(s));
            } catch (RuntimeException e) {
                log.error("Subscription check failed for organisation {}", s.orgId(), e);
            }
        }
        if (changed > 0) log.info("Subscription check: {} subscription(s) changed or notified", changed);
    }

    /** Runs as the organisation. Returns 1 when something was changed or sent. */
    private int process(Sub s) {
        LocalDate today = LocalDate.now(NAIROBI);
        OffsetDateTime now = OffsetDateTime.now(NAIROBI);
        AppProperties.Subscription cfg = props.subscription();
        int graceDays = cfg == null ? 7 : cfg.graceDays();
        int reminderDays = cfg == null ? 5 : cfg.reminderDays();

        if ("ACTIVE".equals(s.status()) && s.periodEnd().isBefore(today)) {
            if (s.cancelAtPeriodEnd()) {
                jdbc.sql("UPDATE subscriptions SET status = 'CANCELLED', cancelled_at = now() WHERE id = ? AND status = 'ACTIVE'")
                        .param(s.id()).update();
                setOrgStatus(s.orgId(), "CANCELLED");
                log.info("Subscription {} of org {} cancelled at period end", s.id(), s.orgId());
                return 1;
            }
            // The period end date is the last paid day; grace runs to the end of periodEnd + graceDays
            OffsetDateTime graceEnds = s.periodEnd().plusDays(graceDays + 1L).atStartOfDay(NAIROBI).toOffsetDateTime();
            int updated = jdbc.sql("""
                            UPDATE subscriptions SET status = 'PAST_DUE', grace_ends_at = ?
                             WHERE id = ? AND status = 'ACTIVE'""")
                    .param(graceEnds).param(s.id()).update();
            if (updated == 0) return 0;
            setOrgStatus(s.orgId(), "PAST_DUE");
            notifyOwner(s, "SUBSCRIPTION_PAST_DUE", graceEnds, s.periodEnd());
            log.info("Subscription {} of org {} is past due; grace until {}", s.id(), s.orgId(), graceEnds);
            return 1;
        }
        if ("ACTIVE".equals(s.status())) {
            long daysLeft = ChronoUnit.DAYS.between(today, s.periodEnd());
            if (daysLeft <= reminderDays) {
                return notifyOwner(s, "SUBSCRIPTION_RENEWAL_DUE", null, s.periodEnd().minusDays(reminderDays + 1L));
            }
            return 0;
        }
        if ("PAST_DUE".equals(s.status()) && s.graceEndsAt() != null && !now.isBefore(s.graceEndsAt())) {
            return notifyOwner(s, "SUBSCRIPTION_LAPSED", s.graceEndsAt(), s.periodEnd());
        }
        return 0;
    }

    /**
     * Sends a template to the organisation owner unless it already went out since {@code sentSince}
     * (one message per template per period; a renewal moves the period end, so the next one goes out).
     */
    private int notifyOwner(Sub s, String template, OffsetDateTime graceEnds, LocalDate sentSince) {
        Owner owner = jdbc.sql("""
                        SELECT u.id, u.first_name, u.email::text, u.phone FROM users u
                          JOIN user_roles ur ON ur.user_id = u.id
                          JOIN roles r ON r.id = ur.role_id AND r.code = 'ORG_OWNER'
                         WHERE u.status = 'ACTIVE' AND u.deleted_at IS NULL
                         ORDER BY u.created_at LIMIT 1""")
                .query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElse(null);
        if (owner == null) return 0;
        boolean sent = jdbc.sql("""
                        SELECT count(*) > 0 FROM notifications
                         WHERE template_code = ? AND recipient_id = ? AND created_at >= ?""")
                .param(template).param(owner.id()).param(sentSince.atStartOfDay(NAIROBI).toOffsetDateTime())
                .query(Boolean.class).single();
        if (sent) return 0;

        Map<String, Object> vars = new HashMap<>();
        vars.put("first_name", owner.firstName());
        vars.put("org_name", s.orgName());
        vars.put("plan_name", s.planName());
        vars.put("amount", s.price() == null ? "" : s.price().stripTrailingZeros().toPlainString());
        vars.put("period_end", s.periodEnd().format(DAY));
        vars.put("grace_end", graceEnds == null ? "" : graceEnds.minusDays(1).atZoneSameInstant(NAIROBI).format(DAY));
        vars.put("link", props.web().frontendUrl().replaceAll("/+$", "") + "/settings/billing");
        notifications.queue(Channel.EMAIL, Recipient.USER, owner.id(), owner.email(), template, vars);
        notifications.queue(Channel.SMS, Recipient.USER, owner.id(), owner.phone(), template, vars);
        return 1;
    }

    /** An admin suspension always wins over billing status. */
    private void setOrgStatus(UUID orgId, String status) {
        jdbc.sql("UPDATE organizations SET status = ? WHERE id = ? AND status <> 'SUSPENDED'")
                .param(status).param(orgId).update();
    }
}
