package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.OnboardingDtos.OnboardingStatus;
import com.propertypro.dto.OnboardingDtos.ProfileRequest;
import com.propertypro.dto.OnboardingDtos.StepStatus;
import com.propertypro.dto.OnboardingDtos.TrialInfo;
import com.propertypro.entity.Subscription;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class OnboardingServiceImpl implements OnboardingService {

    private record Row(String businessType, String unitsRange, String portfolioFocus, Set<String> skipped,
                       OffsetDateTime completedAt) {}

    private record Counts(long properties, long units, long tenants, long channels, long verifiedChannels,
                          long staff) {}

    private final JdbcClient jdbc;
    private final PlanGate planGate;

    @Override
    @Transactional
    public OnboardingStatus status() {
        ensureRow();
        Row row = jdbc.sql("""
                        SELECT business_type, units_range, portfolio_focus, skipped_steps, completed_at
                          FROM onboarding_progress""")
                .query((rs, n) -> new Row(rs.getString(1), rs.getString(2), rs.getString(3),
                        Set.copyOf(Arrays.asList((String[]) rs.getArray(4).getArray())),
                        rs.getObject(5, OffsetDateTime.class)))
                .single();
        Counts c = jdbc.sql("""
                        SELECT (SELECT count(*) FROM properties WHERE deleted_at IS NULL),
                               (SELECT count(*) FROM units WHERE deleted_at IS NULL),
                               (SELECT count(*) FROM tenants WHERE deleted_at IS NULL),
                               (SELECT count(*) FROM payment_channels WHERE active),
                               (SELECT count(*) FROM payment_channels WHERE active AND verified_at IS NOT NULL),
                               (SELECT count(*) FROM users WHERE user_type = 'STAFF' AND deleted_at IS NULL)""")
                .query((rs, n) -> new Counts(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4),
                        rs.getLong(5), rs.getLong(6)))
                .single();

        List<StepStatus> steps = Arrays.stream(Step.values()).map(s -> {
            long count = switch (s) {
                case ACCOUNT -> 1;
                case PROPERTY -> c.properties();
                case UNITS -> c.units();
                case TENANTS -> c.tenants();
                case MPESA -> c.channels();
                case TEAM -> c.staff() - 1;   // the owner who signed up does not count
            };
            String status = count > 0 ? "DONE" : row.skipped().contains(s.name()) ? "SKIPPED" : "TODO";
            return new StepStatus(s.name(), s.title, s.optional, status, Math.max(count, 0));
        }).toList();

        long finished = steps.stream().filter(s -> !"TODO".equals(s.status())).count();
        int percent = (int) Math.round(finished * 100.0 / steps.size());
        String next = steps.stream().filter(s -> "TODO".equals(s.status())).map(StepStatus::key).findFirst().orElse(null);
        boolean canComplete = c.properties() > 0 && c.units() > 0;

        return new OnboardingStatus(row.businessType(), row.unitsRange(), row.portfolioFocus(), steps, percent, next,
                canComplete, row.completedAt() != null, row.completedAt(), c.verifiedChannels() > 0, trial());
    }

    @Override
    @Transactional
    public OnboardingStatus saveProfile(ProfileRequest req) {
        ensureRow();
        jdbc.sql("""
                        UPDATE onboarding_progress
                           SET business_type = coalesce(?, business_type),
                               units_range = coalesce(?, units_range),
                               portfolio_focus = coalesce(?, portfolio_focus)""")
                .param(req.businessType()).param(req.unitsRange()).param(req.portfolioFocus())
                .update();
        return status();
    }

    @Override
    @Transactional
    public OnboardingStatus skip(String key, boolean skip) {
        Step step = parse(key);
        if (!step.optional) {
            throw ApiException.badRequest("STEP_REQUIRED", "\"" + step.title + "\" cannot be skipped");
        }
        ensureRow();
        String sql = skip
                ? "UPDATE onboarding_progress SET skipped_steps = array_append(array_remove(skipped_steps, ?::text), ?::text)"
                : "UPDATE onboarding_progress SET skipped_steps = array_remove(skipped_steps, ?::text)";
        var stmt = jdbc.sql(sql).param(step.name());
        if (skip) stmt = stmt.param(step.name());
        stmt.update();
        return status();
    }

    @Override
    @Transactional
    public OnboardingStatus complete() {
        OnboardingStatus s = status();
        if (!s.canComplete()) {
            throw ApiException.badRequest("ONBOARDING_INCOMPLETE", "Add at least one property with units first");
        }
        jdbc.sql("UPDATE onboarding_progress SET completed_at = coalesce(completed_at, now())").update();
        return status();
    }

    private void ensureRow() {
        jdbc.sql("""
                        INSERT INTO onboarding_progress (organization_id)
                        VALUES (current_setting('app.current_org')::uuid)
                        ON CONFLICT DO NOTHING""")
                .update();
    }

    private TrialInfo trial() {
        Subscription sub = planGate.currentSubscription();
        OffsetDateTime now = OffsetDateTime.now();
        Long daysLeft = sub.getStatus() == Subscription.Status.TRIALING && sub.getTrialEndsAt() != null
                ? Math.max(0, (long) Math.ceil(Duration.between(now, sub.getTrialEndsAt()).toHours() / 24.0))
                : null;
        return new TrialInfo(sub.getStatus().name(), sub.getTrialEndsAt(), daysLeft, !sub.allowsWrites(now));
    }

    private static Step parse(String key) {
        try {
            return Step.valueOf(key.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Onboarding step " + key);
        }
    }
}
