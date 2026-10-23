package com.propertypro.common;

import com.propertypro.common.BankStatementParser.ParsedStatement;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BankStatementParserTest {

    private static ParsedStatement parse(String csv) throws IOException {
        return BankStatementParser.parse(CsvReader.readRows(
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), BankStatementParser.MAX_LINES + 1));
    }

    @Test
    void findsTheHeaderBelowTheAccountDetailsAndKeepsOnlyCredits() throws IOException {
        ParsedStatement s = parse("""
                Account Name,SAMREX PROPERTIES
                Account Number,1234567890
                Period,01/10/2026 - 31/10/2026

                Transaction Date,Value Date,Narration,Reference,Debit,Credit,Balance
                01/10/2026,01/10/2026,Opening balance,,,,"10,000.00"
                02/10/2026,02/10/2026,MPESA C2B 254712345678 1234567890#KLM-B4 JOHN,FT26275ABC,,"15,000.00","25,000.00"
                03/10/2026,03/10/2026,Bank charges,CHG001,50.00,,"24,950.00"
                05-Oct-2026,05-Oct-2026,TRANSFER FROM MARY KLM B7,,,"12,500","37,450.00"
                ,,Total,,50.00,"27,500.00",
                """);

        assertThat(s.credits()).hasSize(2);
        var first = s.credits().getFirst();
        assertThat(first.line()).isEqualTo(7);
        assertThat(first.credit().reference()).isEqualTo("FT26275ABC");
        assertThat(first.credit().amount()).isEqualByComparingTo("15000.00");
        assertThat(first.credit().paidAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(first.credit().balanceAfter()).isEqualByComparingTo("25000.00");

        var second = s.credits().get(1);
        assertThat(second.credit().paidAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 10, 5));
        assertThat(second.credit().reference()).startsWith("STMT-");   // no bank reference: fingerprint
        assertThat(s.problems()).isEmpty();
    }

    @Test
    void theSameLineAlwaysGetsTheSameFingerprint() throws IOException {
        String csv = """
                Date,Description,Money In,Balance
                2026-10-02,KLM-B4 rent,5000,5000
                """;
        assertThat(parse(csv).credits().getFirst().credit().reference())
                .isEqualTo(parse(csv).credits().getFirst().credit().reference());
    }

    @Test
    void singleAmountColumnSkipsNegativesAndDebits() throws IOException {
        ParsedStatement s = parse("""
                Posting Date;Particulars;Amount;Balance
                02/10/2026 09:15;KLM-B4;8000.00;8000.00
                03/10/2026 10:00;ATM withdrawal;-2000.00;6000.00
                04/10/2026;Cheque;(1000.00);5000.00
                """);
        assertThat(s.credits()).hasSize(1);
        assertThat(s.credits().getFirst().credit().amount()).isEqualByComparingTo("8000");
    }

    @Test
    void unreadableDatesAreReportedNotGuessed() throws IOException {
        ParsedStatement s = parse("""
                Date,Narration,Credit
                sometime,KLM-B4,1000
                """);
        assertThat(s.credits()).isEmpty();
        assertThat(s.problems()).singleElement().asString().contains("Line 2");
    }

    @Test
    void refusesAFileWithoutStatementColumns() {
        assertThatThrownBy(() -> parse("""
                Name,Phone
                John,0712345678
                """)).isInstanceOf(ApiException.class);
    }

    @Test
    void amountsInBankFormats() {
        assertThat(BankStatementParser.amount("KES 12,500.50")).isEqualByComparingTo(new BigDecimal("12500.50"));
        assertThat(BankStatementParser.amount("1,000.00 CR")).isEqualByComparingTo("1000");
        assertThat(BankStatementParser.amount("1,000.00 DR")).isEqualByComparingTo("-1000");
        assertThat(BankStatementParser.amount("")).isNull();
    }
}
