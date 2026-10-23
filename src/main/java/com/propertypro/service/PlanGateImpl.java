package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.PlanDtos.UnitAllowance;
import com.propertypro.entity.Plan;
import com.propertypro.entity.Subscription;
import com.propertypro.repository.SubscriptionRepository;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

@Service
@RequiredArgsConstructor
public class PlanGateImpl implements PlanGate {

    private final SubscriptionRepository subscriptions;

    @Override
    @Transactional(readOnly = true)
    public Subscription currentSubscription() {
        return subscriptions
                .findFirstByOrganizationIdAndStatusIn(TenantContext.requireOrganizationId(), Subscription.LIVE)
                .orElseThrow(() -> new ApiException(HttpStatus.PAYMENT_REQUIRED, "NO_SUBSCRIPTION",
                        "Your subscription has ended. Renew it to continue."));
    }

    /** Call before any create/update: expired trials and lapsed subscriptions are read-only. */
    @Override
    @Transactional(readOnly = true)
    public void requireWritable() {
        if (!currentSubscription().allowsWrites(OffsetDateTime.now())) {
            throw ApiException.planLimit("SUBSCRIPTION_READ_ONLY",
                    "Your subscription is read-only. Renew or upgrade to make changes.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public void requireFeature(String feature) {
        Plan plan = currentSubscription().getPlan();
        if (!plan.hasFeature(feature)) {
            throw ApiException.planLimit("FEATURE_NOT_IN_PLAN",
                    "Your " + plan.getName() + " plan does not include this feature. Upgrade to use it.");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public UnitAllowance unitAllowance() {
        Subscription sub = currentSubscription();
        Plan plan = sub.getPlan();
        if (plan.getUnitLimit() == null) {
            return new UnitAllowance(null, false);            // Enterprise: unlimited
        }
        if (plan.getExtraUnitPrice() != null) {
            return new UnitAllowance(null, true);             // Over the limit is allowed and billed
        }
        return new UnitAllowance(plan.getUnitLimit() + sub.getExtraUnits(), false);
    }

    /** Throws if adding {@code adding} units to {@code current} would pass a hard limit. */
    @Override
    public void requireUnitCapacity(long current, int adding) {
        UnitAllowance allowance = unitAllowance();
        if (allowance.maxUnits() != null && current + adding > allowance.maxUnits()) {
            throw ApiException.planLimit("UNIT_LIMIT_REACHED",
                    "Your plan allows " + allowance.maxUnits() + " units and you have " + current
                            + ". Upgrade to add more.");
        }
    }
}
