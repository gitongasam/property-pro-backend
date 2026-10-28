package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import com.propertypro.config.AppProperties;
import com.propertypro.scheduler.SubscriptionJobs;
import com.propertypro.service.NotificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A paid subscription that is not renewed: reminder, grace period, read-only, each message once.
 * The job is built by hand because scheduled jobs are switched off in tests; dates are moved
 * in the database instead of waiting for them.
 */
class SubscriptionExpiryIntegrationTest extends IntegrationTestBase {

    static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    @Autowired JdbcClient jdbcClient;
    @Autowired NotificationService notifications;
    @Autowired AppProperties props;

    SubscriptionJobs jobs;

    @BeforeEach
    void buildJob() {
        jobs = new SubscriptionJobs(system, jdbcClient, notifications, props);
    }

    @Test
    void unpaidSubscriptionGetsGraceThenBecomesReadOnly() throws Exception {
        JsonNode account = signup("Expiry Org", uniqueEmail("expiry"), uniquePhone());
        String token = account.get("accessToken").asText();
        UUID org = orgId(account);
        LocalDate today = LocalDate.now(NAIROBI);
        makePaid(org, today.minusDays(1), false);

        jobs.run();
        jobs.run();

        assertThat(subscriptionStatus(org)).isEqualTo("PAST_DUE");
        assertThat(organisationStatus(org)).isEqualTo("PAST_DUE");
        // The last paid day was yesterday; 7 days of grace run to the end of yesterday + 7
        assertThat(graceEndsAt(org).toInstant())
                .isEqualTo(today.plusDays(7).atStartOfDay(NAIROBI).toInstant());
        assertThat(sent(org, "SUBSCRIPTION_PAST_DUE")).isEqualTo(2);   // one email + one SMS, not repeated

        // Still fully usable during grace
        createProperty(token, "GRC").andExpect(status().isCreated());

        // Grace runs out
        system.asSystem(() -> jdbc.update(
                "UPDATE subscriptions SET grace_ends_at = now() - interval '1 hour' WHERE organization_id = ?", org));
        jobs.run();
        jobs.run();

        assertThat(sent(org, "SUBSCRIPTION_LAPSED")).isEqualTo(2);
        createProperty(token, "RO1")
                .andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("SUBSCRIPTION_READ_ONLY"));
        mvc.perform(auth(get("/api/v1/properties"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void ownerIsRemindedOnceBeforeThePeriodEnds() {
        UUID org = orgId(signupQuietly("Reminder Org"));
        makePaid(org, LocalDate.now(NAIROBI).plusDays(3), false);

        jobs.run();
        jobs.run();

        assertThat(subscriptionStatus(org)).isEqualTo("ACTIVE");
        assertThat(sent(org, "SUBSCRIPTION_RENEWAL_DUE")).isEqualTo(2);
    }

    @Test
    void noReminderWhileThePeriodHasLongToRun() {
        UUID org = orgId(signupQuietly("Far Off Org"));
        makePaid(org, LocalDate.now(NAIROBI).plusDays(20), false);

        jobs.run();

        assertThat(sent(org, "SUBSCRIPTION_RENEWAL_DUE")).isZero();
    }

    @Test
    void cancelledAtPeriodEndEndsWithoutGrace() {
        UUID org = orgId(signupQuietly("Cancelling Org"));
        makePaid(org, LocalDate.now(NAIROBI).minusDays(1), true);

        jobs.run();

        assertThat(subscriptionStatus(org)).isEqualTo("CANCELLED");
        assertThat(organisationStatus(org)).isEqualTo("CANCELLED");
        assertThat(sent(org, "SUBSCRIPTION_PAST_DUE")).isZero();
    }

    @Test
    void anAdminSuspensionIsNeverOverwritten() {
        UUID org = orgId(signupQuietly("Suspended Org"));
        makePaid(org, LocalDate.now(NAIROBI).minusDays(1), false);
        system.asSystem(() -> jdbc.update("UPDATE organizations SET status = 'SUSPENDED' WHERE id = ?", org));

        jobs.run();

        assertThat(subscriptionStatus(org)).isEqualTo("PAST_DUE");
        assertThat(organisationStatus(org)).isEqualTo("SUSPENDED");
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode signupQuietly(String name) {
        try {
            return signup(name, uniqueEmail("sub"), uniquePhone());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Turns the trial into a paid monthly subscription whose last paid day is {@code periodEnd}. */
    private void makePaid(UUID org, LocalDate periodEnd, boolean cancelAtPeriodEnd) {
        system.asSystem(() -> {
            jdbc.update("""
                            UPDATE subscriptions SET status = 'ACTIVE', trial_ends_at = NULL, grace_ends_at = NULL,
                                   current_period_start = ?, current_period_end = ?, cancel_at_period_end = ?
                             WHERE organization_id = ?""",
                    periodEnd.minusMonths(1).plusDays(1), periodEnd, cancelAtPeriodEnd, org);
            jdbc.update("UPDATE organizations SET status = 'ACTIVE' WHERE id = ?", org);
            return null;
        });
    }

    private org.springframework.test.web.servlet.ResultActions createProperty(String token, String code) throws Exception {
        return mvc.perform(authJson(post("/api/v1/properties"), token,
                "{\"code\":\"" + code + "\",\"name\":\"Property " + code + "\",\"propertyType\":\"RESIDENTIAL\"}"));
    }

    private String subscriptionStatus(UUID org) {
        return system.asSystem(() -> jdbc.queryForObject(
                "SELECT status FROM subscriptions WHERE organization_id = ?", String.class, org));
    }

    private OffsetDateTime graceEndsAt(UUID org) {
        return system.asSystem(() -> jdbc.queryForObject(
                "SELECT grace_ends_at FROM subscriptions WHERE organization_id = ?", OffsetDateTime.class, org));
    }

    private String organisationStatus(UUID org) {
        return system.asSystem(() -> jdbc.queryForObject(
                "SELECT status FROM organizations WHERE id = ?", String.class, org));
    }

    private int sent(UUID org, String template) {
        return system.asSystem(() -> jdbc.queryForObject(
                "SELECT count(*) FROM notifications WHERE organization_id = ? AND template_code = ?",
                Integer.class, org, template));
    }
}
