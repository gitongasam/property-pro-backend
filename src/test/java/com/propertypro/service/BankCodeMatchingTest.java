package com.propertypro.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Finding the unit code in what the payer typed or the statement narration. */
class BankCodeMatchingTest {

    private static final Map<String, String> CODES = Map.of("KLMB4", "KLM-B4", "KLMB7", "KLM-B7", "T0042", "T-0042");

    @Test
    void codeAfterTheBankAccount() {
        assertThat(BankPaymentServiceImpl.findCode("MPESA C2B 254712345678 1234567890#KLM-B4 JOHN", CODES))
                .isEqualTo("KLM-B4");
    }

    @Test
    void codeTypedWithSpacesOrLowerCase() {
        assertThat(BankPaymentServiceImpl.findCode("transfer from mary klm b7 october", CODES)).isEqualTo("KLM-B7");
    }

    @Test
    void tenantAccountCode() {
        assertThat(BankPaymentServiceImpl.findCode("RTGS T-0042 RENT", CODES)).isEqualTo("T-0042");
    }

    @Test
    void noCodeOrTwoCodesIsLeftForStaff() {
        assertThat(BankPaymentServiceImpl.findCode("SALARY OCTOBER", CODES)).isNull();
        assertThat(BankPaymentServiceImpl.findCode("KLM-B4 AND KLM-B7", CODES)).isNull();
    }
}
