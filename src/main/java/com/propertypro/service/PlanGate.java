package com.propertypro.service;

import com.propertypro.dto.PlanDtos.UnitAllowance;
import com.propertypro.entity.Subscription;

/**
 * Plan checks used by every module: is the subscription usable, does the plan
 * include a feature, how many units may the organisation have.
 */
public interface PlanGate {

    Subscription currentSubscription();

    /** Call before any create/update: expired trials and lapsed subscriptions are read-only. */
    void requireWritable();

    void requireFeature(String feature);

    UnitAllowance unitAllowance();

    /** Throws if adding {@code adding} units to {@code current} would pass a hard limit. */
    void requireUnitCapacity(long current, int adding);
}
