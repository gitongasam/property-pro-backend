package com.propertypro.dto;

import java.math.BigDecimal;
import java.util.Map;

/** Plan list and plan-limit views. */
public final class PlanDtos {

    private PlanDtos() {}

    /** Units the organisation may hold; {@code null} = no hard limit. */
    public record UnitAllowance(Integer maxUnits, boolean overageBilled) {}

    public record PlanView(String code, String name, String description, String currency,
                           BigDecimal monthlyPrice, BigDecimal annualPrice, Integer unitLimit,
                           Integer userLimit, BigDecimal extraUnitPrice, int smsQuotaMonthly,
                           Map<String, Boolean> features) {}
}
