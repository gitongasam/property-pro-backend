package com.propertypro.scheduler;

import com.propertypro.dto.BillingDtos.BillingRunResult;
import com.propertypro.entity.Invoice;
import com.propertypro.entity.Lease;
import com.propertypro.repository.InvoiceRepository;
import com.propertypro.repository.LeaseRepository;
import com.propertypro.service.BillingService;
import com.propertypro.service.NotificationService.Channel;
import com.propertypro.service.NotificationService.Recipient;
import com.propertypro.service.NotificationService;
import com.propertypro.tenancy.SystemExecutor;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * The daily money jobs, per organisation:
 *   05:30  raise invoices for every lease whose billing date has come
 *   06:00  late-payment penalties
 *   09:00  SMS reminders: 3 days before the due date and on the day
 *
 * Each lease is billed in its own transaction, so one bad lease never blocks
 * the others. Organisations whose subscription is read-only are skipped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BillingJobs {

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final SystemExecutor system;
    private final LeaseRepository leases;
    private final InvoiceRepository invoices;
    private final BillingService billing;
    private final NotificationService notifications;
    private final JdbcClient jdbc;

    @Value("${app.jobs.enabled:true}")
    private boolean jobsEnabled;

    // ---------------------------------------------------------------- schedules

    @Scheduled(cron = "0 30 5 * * *", zone = "Africa/Nairobi")
    public void dailyBilling() {
        if (!jobsEnabled) return;
        LocalDate today = LocalDate.now(NAIROBI);
        for (UUID org : billableOrganisations()) {
            BillingRunResult r = runForOrganisation(org, null, today);
            log.info("Billing {} for org {}: {} invoices, KES {}", today, org, r.invoicesCreated(), r.totalBilled());
        }
    }

    @Scheduled(cron = "0 0 6 * * *", zone = "Africa/Nairobi")
    public void dailyPenalties() {
        if (!jobsEnabled) return;
        LocalDate today = LocalDate.now(NAIROBI);
        for (UUID org : billableOrganisations()) {
            try {
                int n = system.asOrganization(org, null, () -> billing.applyPenalties(today));
                if (n > 0) log.info("Org {}: {} late penalties", org, n);
            } catch (RuntimeException e) {
                log.error("Penalty run failed for org {}", org, e);
            }
        }
    }

    @Scheduled(cron = "0 0 9 * * *", zone = "Africa/Nairobi")
    public void dailyReminders() {
        if (!jobsEnabled) return;
        LocalDate today = LocalDate.now(NAIROBI);
        for (UUID org : billableOrganisations()) {
            try {
                system.asOrganization(org, null, () -> {
                    remind(today.plusDays(3));
                    remind(today);
                    return null;
                });
            } catch (RuntimeException e) {
                log.error("Reminder run failed for org {}", org, e);
            }
        }
    }

    // ------------------------------------------------------------- the billing run

    /** Bills every due lease of one organisation. Also used by the "Run billing now" button. */
    public BillingRunResult runForOrganisation(UUID organizationId, UUID userId, LocalDate runDate) {
        List<UUID> due = system.asOrganization(organizationId, userId, () ->
                leases.findDueForBilling(Lease.LIVE, runDate).stream().map(Lease::getId).toList());
        int created = 0, skipped = 0;
        BigDecimal total = BigDecimal.ZERO;
        List<String> problems = new ArrayList<>();
        for (UUID leaseId : due) {
            try {
                List<Invoice> raised = system.asOrganization(organizationId, userId, () -> billing.billLease(leaseId, runDate));
                created += raised.size();
                for (Invoice i : raised) total = total.add(i.getTotal());
            } catch (RuntimeException e) {
                skipped++;
                problems.add("Lease " + leaseId + ": " + e.getMessage());
                log.warn("Billing lease {} failed: {}", leaseId, e.getMessage());
            }
        }
        return new BillingRunResult(runDate, created, total, skipped, problems.size() > 20 ? problems.subList(0, 20) : problems);
    }

    /** The current organisation, now (from the API). */
    public BillingRunResult runNow(LocalDate runDate) {
        return runForOrganisation(TenantContext.requireOrganizationId(), TenantContext.userId().orElse(null),
                runDate == null ? LocalDate.now(NAIROBI) : runDate);
    }

    private void remind(LocalDate dueDate) {
        for (Invoice inv : invoices.findOpenDueOn(dueDate)) {
            var t = inv.getTenant();
            var payTo = billing.payTo(inv);
            notifications.queue(Channel.SMS, Recipient.TENANT, t.getId(), t.getPhone(), "RENT_REMINDER", Map.of(
                    "tenant_name", t.getName(),
                    "balance", inv.getBalance().toPlainString(),
                    "unit", inv.getUnit() == null ? "" : inv.getUnit().getUnitNo(),
                    "due_date", inv.getDueDate().format(DAY),
                    "paybill", payTo.paybill(),
                    "payment_code", payTo.account()));
        }
    }

    /** Organisations whose subscription still allows writes (trial running, active, or in grace) and not suspended. */
    private List<UUID> billableOrganisations() {
        return system.asSystem(() -> jdbc.sql("""
                        SELECT o.id FROM organizations o
                          JOIN subscriptions s ON s.organization_id = o.id
                         WHERE o.deleted_at IS NULL
                           AND o.status <> 'SUSPENDED'
                           AND (   (s.status = 'TRIALING'  AND (s.trial_ends_at IS NULL OR s.trial_ends_at > now()))
                                OR  s.status = 'ACTIVE'
                                OR (s.status = 'PAST_DUE'  AND (s.grace_ends_at IS NULL OR s.grace_ends_at > now())))""")
                .query(UUID.class).list());
    }
}
