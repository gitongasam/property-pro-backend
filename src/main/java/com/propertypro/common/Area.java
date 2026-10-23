package com.propertypro.common;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/** Amounts charged by floor area: rates are KES per sq ft per month. */
public final class Area {

    private Area() {}

    /** area × rate × months, rounded to the shilling cent. */
    public static BigDecimal amount(BigDecimal sqft, BigDecimal ratePerMonth, int months) {
        return Money.of(sqft.multiply(ratePerMonth).multiply(BigDecimal.valueOf(months)));
    }

    /** The rate that gives this amount, e.g. after a fixed rent escalation. */
    public static BigDecimal rate(BigDecimal amount, BigDecimal sqft, int months) {
        return amount.divide(sqft.multiply(BigDecimal.valueOf(months)), 4, RoundingMode.HALF_UP);
    }

    /** "1,200 sq ft @ KES 120/sq ft", shown on invoice lines. */
    public static String describe(BigDecimal sqft, BigDecimal ratePerMonth) {
        return new DecimalFormat("#,##0.##").format(sqft) + " sq ft @ KES "
                + new DecimalFormat("#,##0.00##").format(ratePerMonth) + "/sq ft";
    }
}
