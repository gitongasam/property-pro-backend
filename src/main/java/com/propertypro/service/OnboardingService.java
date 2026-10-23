package com.propertypro.service;

import com.propertypro.dto.OnboardingDtos.OnboardingStatus;
import com.propertypro.dto.OnboardingDtos.ProfileRequest;

/**
 * The set-up checklist shown after sign-up. Whether a step is done is read
 * from real data (a property exists, units exist...); only the business
 * profile, skipped steps and completion are stored.
 */
public interface OnboardingService {

    enum Step {
        ACCOUNT("Create your account", false),
        PROPERTY("Add your first property", false),
        UNITS("Add units", false),
        TENANTS("Add tenants and balances", true),
        MPESA("Connect M-Pesa", true),
        TEAM("Invite your team", true);

        public final String title;
        public final boolean optional;

        Step(String title, boolean optional) {
            this.title = title;
            this.optional = optional;
        }
    }

    OnboardingStatus status();

    OnboardingStatus saveProfile(ProfileRequest req);

    OnboardingStatus skip(String key, boolean skip);

    OnboardingStatus complete();
}
