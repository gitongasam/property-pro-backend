package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.OrganizationDtos.Defaults;
import com.propertypro.dto.OrganizationDtos.ProfileRequest;
import com.propertypro.dto.OrganizationDtos.ProfileResponse;
import com.propertypro.entity.Organization;
import com.propertypro.repository.OrganizationRepository;
import com.propertypro.tenancy.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class OrganizationServiceImpl implements OrganizationService {

    private final OrganizationRepository organizations;
    private final PlanGate planGate;

    @Override
    @Transactional(readOnly = true)
    public ProfileResponse get() {
        return toResponse(current());
    }

    @Override
    @Transactional
    public ProfileResponse update(ProfileRequest req) {
        planGate.requireWritable();
        Organization o = current();
        o.setName(req.name().trim());
        o.setLegalName(trim(req.legalName()));
        o.setKraPin(trim(req.kraPin()));
        o.setEmail(trim(req.email()));
        o.setPhone(trim(req.phone()));
        o.setAddress(trim(req.address()));
        o.setCounty(trim(req.county()));
        o.setTown(trim(req.town()));
        o.setLogoUrl(trim(req.logoUrl()));
        if (req.defaults() != null) {
            Map<String, Object> s = new HashMap<>(o.getSettings() == null ? Map.of() : o.getSettings());
            Defaults d = req.defaults();
            put(s, "management_fee_vat_rate", d.managementFeeVatRate());
            put(s, "default_billing_day", d.defaultBillingDay());
            put(s, "default_due_days", d.defaultDueDays());
            put(s, "reminder_days_before", d.reminderDaysBefore());
            put(s, "sms_sender_id", trim(d.smsSenderId()));
            o.setSettings(s);   // a new map so Hibernate sees the JSON change
        }
        return toResponse(organizations.saveAndFlush(o));
    }

    @Override
    public Organization current() {
        return organizations.findById(TenantContext.requireOrganizationId())
                .orElseThrow(() -> ApiException.notFound("Organisation"));
    }

    private static ProfileResponse toResponse(Organization o) {
        Map<String, Object> s = o.getSettings() == null ? Map.of() : o.getSettings();
        Defaults d = new Defaults(
                decimal(s.get("management_fee_vat_rate"), new BigDecimal("16")),
                integer(s.get("default_billing_day"), 1),
                integer(s.get("default_due_days"), 5),
                integer(s.get("reminder_days_before"), 3),
                s.get("sms_sender_id") == null ? null : s.get("sms_sender_id").toString());
        return new ProfileResponse(o.getId(), o.getName(), o.getSlug(), o.getLegalName(), o.getKraPin(), o.getEmail(),
                o.getPhone(), o.getAddress(), o.getCounty(), o.getTown(), o.getLogoUrl(), o.getCurrency(),
                o.getTimezone(), o.getStatus().name(), d, o.getCreatedAt());
    }

    private static void put(Map<String, Object> s, String key, Object value) {
        if (value != null) s.put(key, value);
    }

    private static String trim(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static BigDecimal decimal(Object v, BigDecimal fallback) {
        try {
            return v == null ? fallback : new BigDecimal(v.toString());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static Integer integer(Object v, int fallback) {
        try {
            return v == null ? fallback : Integer.valueOf(new BigDecimal(v.toString()).intValue());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
