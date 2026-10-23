package com.propertypro.scheduler;

import com.propertypro.config.AppProperties;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.service.NotificationService;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Trial nudges, once each per organisation, emailed to the owner:
 * day 1 with no property, day 3 with no M-Pesa channel, 4 days before the
 * trial ends, and when it has ended. An ended trial is read-only (see
 * {@code Subscription.allowsWrites}) but its data stays visible, so the
 * subscription is not changed here.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.jobs.enabled", havingValue = "true", matchIfMissing = true)
public class TrialJobs {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy");

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final NotificationService notifications;
    private final AppProperties props;

    private record Trial(UUID orgId, String orgName, OffsetDateTime createdAt, OffsetDateTime trialEndsAt) {}

    private record Owner(UUID id, String firstName, String email, String phone) {}

    @Scheduled(cron = "0 0 10 * * *", zone = "Africa/Nairobi")
    public void run() {
        List<Trial> trials = system.asSystem(() -> jdbc.sql("""
                        SELECT o.id, o.name, o.created_at, s.trial_ends_at
                          FROM organizations o
                          JOIN subscriptions s ON s.organization_id = o.id AND s.status = 'TRIALING'
                         WHERE o.deleted_at IS NULL""")
                .query((rs, n) -> new Trial(rs.getObject(1, UUID.class), rs.getString(2),
                        rs.getObject(3, OffsetDateTime.class), rs.getObject(4, OffsetDateTime.class)))
                .list());
        int sent = 0;
        for (Trial t : trials) {
            try {
                sent += system.asOrganization(t.orgId(), null, () -> nudge(t));
            } catch (RuntimeException e) {
                log.error("Trial reminders failed for organisation {}", t.orgId(), e);
            }
        }
        if (sent > 0) log.info("Queued {} trial reminder(s)", sent);
    }

    private int nudge(Trial t) {
        Owner owner = jdbc.sql("""
                        SELECT u.id, u.first_name, u.email::text, u.phone FROM users u
                          JOIN user_roles ur ON ur.user_id = u.id
                          JOIN roles r ON r.id = ur.role_id AND r.code = 'ORG_OWNER'
                         WHERE u.status = 'ACTIVE' AND u.deleted_at IS NULL
                         ORDER BY u.created_at LIMIT 1""")
                .query((rs, n) -> new Owner(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4)))
                .optional().orElse(null);
        if (owner == null) return 0;

        OffsetDateTime now = OffsetDateTime.now(NAIROBI);
        long ageDays = Duration.between(t.createdAt(), now).toDays();
        String base = props.web().frontendUrl().replaceAll("/+$", "");
        Map<String, Object> vars = new HashMap<>();
        vars.put("first_name", owner.firstName());
        vars.put("org_name", t.orgName());
        vars.put("trial_end", t.trialEndsAt() == null ? "" : t.trialEndsAt().atZoneSameInstant(NAIROBI).format(DAY));

        // At most one nudge a day, the most important first; each goes out once.
        if (t.trialEndsAt() != null && !now.isBefore(t.trialEndsAt())) {
            vars.put("link", base + "/settings/billing");
            return send(owner, "TRIAL_EXPIRED", vars, false);
        }
        if (t.trialEndsAt() != null && Duration.between(now, t.trialEndsAt()).toDays() < 4) {
            vars.put("link", base + "/settings/billing");
            if (send(owner, "TRIAL_ENDING", vars, true) > 0) return 1;
        }
        if (ageDays >= 1 && count("SELECT count(*) FROM properties WHERE deleted_at IS NULL") == 0) {
            vars.put("link", base + "/onboarding");
            if (send(owner, "TRIAL_ADD_PROPERTY", vars, false) > 0) return 1;
        }
        if (ageDays >= 3 && count("SELECT count(*) FROM payment_channels WHERE active") == 0) {
            vars.put("link", base + "/settings/payments");
            if (send(owner, "TRIAL_CONNECT_MPESA", vars, false) > 0) return 1;
        }
        return 0;
    }

    private int send(Owner owner, String template, Map<String, Object> vars, boolean alsoSms) {
        if (notifications.alreadySent(template, owner.id())) return 0;
        notifications.queue(Channel.EMAIL, Recipient.USER, owner.id(), owner.email(), template, vars);
        if (alsoSms) notifications.queue(Channel.SMS, Recipient.USER, owner.id(), owner.phone(), template, vars);
        return 1;
    }

    private long count(String sql) {
        return jdbc.sql(sql).query(Long.class).single();
    }
}
