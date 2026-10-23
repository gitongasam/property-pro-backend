package com.propertypro.common;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Rent and service charge on commercial space let per sq ft. */
class AreaTest {

    @Test
    void monthlyRentAndServiceCharge() {
        BigDecimal sqft = new BigDecimal("1200");
        assertThat(Area.amount(sqft, new BigDecimal("85"), 1)).isEqualByComparingTo("102000.00");
        assertThat(Area.amount(sqft, new BigDecimal("26"), 1)).isEqualByComparingTo("31200.00");
    }

    @Test
    void quarterlyPeriodIsThreeMonths() {
        assertThat(Area.amount(new BigDecimal("850.5"), new BigDecimal("85"), 3)).isEqualByComparingTo("216877.50");
    }

    @Test
    void rateBackFromEscalatedAmount() {
        // 102,000 + 10% = 112,200 on 1,200 sq ft
        assertThat(Area.rate(new BigDecimal("112200.00"), new BigDecimal("1200"), 1)).isEqualByComparingTo("93.50");
    }

    @Test
    void invoiceWording() {
        assertThat(Area.describe(new BigDecimal("1200"), new BigDecimal("85")))
                .isEqualTo("1,200 sq ft @ KES 85.00/sq ft");
    }
}
