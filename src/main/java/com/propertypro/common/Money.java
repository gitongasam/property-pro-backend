package com.propertypro.common;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** KES amounts: always two decimals, half-up rounding. */
public final class Money {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    public static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Money() {}

    public static BigDecimal of(BigDecimal value) {
        return value == null ? ZERO : value.setScale(2, RoundingMode.HALF_UP);
    }

    public static BigDecimal of(String value) {
        if (value == null || value.isBlank()) {
            return ZERO;
        }
        try {
            return of(new BigDecimal(value.replace(",", "").replace("KES", "").trim()));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("INVALID_AMOUNT", "Not a valid amount: " + value);
        }
    }

    /** amount × percent / 100 */
    public static BigDecimal percent(BigDecimal amount, BigDecimal percent) {
        if (amount == null || percent == null) {
            return ZERO;
        }
        return amount.multiply(percent).divide(HUNDRED, 2, RoundingMode.HALF_UP);
    }

    public static boolean isPositive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }
}
