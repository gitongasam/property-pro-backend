package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The money path: a lease is activated, the move-in invoice is raised, payments are
 * allocated oldest invoice first, extra money becomes credit for the next invoice,
 * billing never charges the same period twice, and a reversal reopens invoices.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BillingAndPaymentsIntegrationTest extends IntegrationTestBase {

    // Start on the 1st with billing on the 1st, so the first month is billed in full (no pro-rating)
    static final LocalDate START = LocalDate.now(ZoneId.of("Africa/Nairobi")).withDayOfMonth(1);

    String token;
    String otherOrgToken;
    String tenantId;
    String leaseId;
    String moveInInvoiceId;
    String firstPaymentId;
    LocalDate nextBillingDate;

    @BeforeAll
    void setUp() throws Exception {
        token = signup("Billing Org", uniqueEmail("billing"), uniquePhone()).get("accessToken").asText();
        otherOrgToken = signup("Other Billing Org", uniqueEmail("other"), uniquePhone()).get("accessToken").asText();

        String propertyId = body(mvc.perform(authJson(post("/api/v1/properties"), token, """
                        {"code":"BIL","name":"Billing Court","propertyType":"RESIDENTIAL"}"""))
                .andExpect(status().isCreated())).get("id").asText();
        String unitId = body(mvc.perform(authJson(post("/api/v1/properties/" + propertyId + "/units/bulk"), token, """
                        {"units":[{"unitNo":"A1","unitType":"ONE_BR","marketRent":20000,"depositAmount":20000}]}"""))
                .andExpect(status().isCreated())).get(0).get("id").asText();
        tenantId = body(mvc.perform(authJson(post("/api/v1/tenants"), token, Map.of(
                        "tenantType", "INDIVIDUAL", "name", "Jane Wanjiku", "phone", uniquePhone())))
                .andExpect(status().isCreated())).get("id").asText();

        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("unitId", unitId);
        lease.put("tenantId", tenantId);
        lease.put("startDate", START.toString());
        lease.put("rentAmount", 20000);
        lease.put("depositAmount", 20000);
        lease.put("billingFrequency", "MONTHLY");
        lease.put("billingDay", 1);
        lease.put("dueDays", 5);
        leaseId = body(mvc.perform(authJson(post("/api/v1/leases"), token, lease))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))).get("id").asText();
    }

    @Test
    @Order(1)
    void activatingTheLeaseRaisesDepositPlusFirstMonth() throws Exception {
        JsonNode lease = body(mvc.perform(auth(post("/api/v1/leases/" + leaseId + "/activate"), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")));
        nextBillingDate = LocalDate.parse(lease.get("nextBillingDate").asText());
        assertThat(nextBillingDate).isEqualTo(START.plusMonths(1));

        List<JsonNode> invoices = invoices();
        assertThat(invoices).hasSize(1);
        JsonNode inv = invoices.getFirst();
        moveInInvoiceId = inv.get("id").asText();
        assertThat(inv.get("status").asText()).isEqualTo("ISSUED");
        assertMoney(inv.get("total"), "40000");
        assertMoney(inv.get("balance"), "40000");
    }

    @Test
    @Order(2)
    void partPaymentLeavesTheInvoicePartlyPaid() throws Exception {
        JsonNode payment = pay(15000, "CASH-001");
        firstPaymentId = payment.get("id").asText();
        assertThat(payment.get("status").asText()).isEqualTo("ALLOCATED");
        assertMoney(payment.get("amountAllocated"), "15000");

        JsonNode inv = invoice(moveInInvoiceId);
        assertThat(inv.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertMoney(inv.get("balance"), "25000");
    }

    @Test
    @Order(3)
    void overpaymentClearsTheInvoiceAndKeepsTheRestAsCredit() throws Exception {
        JsonNode payment = pay(30000, "CASH-002");
        assertThat(payment.get("status").asText()).isEqualTo("PARTIAL");
        assertMoney(payment.get("amountAllocated"), "25000");
        assertMoney(payment.get("unallocated"), "5000");

        JsonNode inv = invoice(moveInInvoiceId);
        assertThat(inv.get("status").asText()).isEqualTo("PAID");
        assertMoney(inv.get("balance"), "0");
    }

    @Test
    @Order(4)
    void monthlyBillingUsesTheCreditAndNeverBillsAPeriodTwice() throws Exception {
        mvc.perform(auth(post("/api/v1/invoices/run-billing").param("date", nextBillingDate.toString()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesCreated").value(1));
        mvc.perform(auth(post("/api/v1/invoices/run-billing").param("date", nextBillingDate.toString()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesCreated").value(0));

        List<JsonNode> invoices = invoices();
        assertThat(invoices).hasSize(2);
        JsonNode rent = invoices.stream().filter(i -> !i.get("id").asText().equals(moveInInvoiceId)).findFirst().orElseThrow();
        assertMoney(rent.get("total"), "20000");
        assertMoney(rent.get("balance"), "15000");     // the 5,000 credit was used
        assertThat(rent.get("status").asText()).isEqualTo("PARTIALLY_PAID");
    }

    @Test
    @Order(5)
    void reversingAPaymentReopensWhatItPaid() throws Exception {
        mvc.perform(authJson(post("/api/v1/payments/" + firstPaymentId + "/reverse"), token,
                        Map.of("reason", "Cheque bounced")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        JsonNode inv = invoice(moveInInvoiceId);
        assertThat(inv.get("status").asText()).isEqualTo("PARTIALLY_PAID");
        assertMoney(inv.get("balance"), "15000");
    }

    @Test
    @Order(6)
    void anotherOrganisationCannotSeeTheseInvoicesOrPayments() throws Exception {
        mvc.perform(auth(get("/api/v1/invoices/" + moveInInvoiceId), otherOrgToken)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/payments/" + firstPaymentId), otherOrgToken)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/invoices"), otherOrgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        // Nor record a payment against this organisation's tenant
        mvc.perform(authJson(post("/api/v1/payments"), otherOrgToken, Map.of(
                        "tenantId", tenantId, "amount", 100, "method", "CASH")))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private JsonNode pay(int amount, String reference) throws Exception {
        return body(mvc.perform(authJson(post("/api/v1/payments"), token, Map.of(
                        "tenantId", tenantId, "amount", amount, "method", "CASH", "reference", reference)))
                .andExpect(status().isCreated()));
    }

    private List<JsonNode> invoices() throws Exception {
        JsonNode page = body(mvc.perform(auth(get("/api/v1/invoices").param("tenantId", tenantId), token))
                .andExpect(status().isOk()));
        List<JsonNode> list = new java.util.ArrayList<>();
        page.get("content").forEach(list::add);
        return list;
    }

    private JsonNode invoice(String id) throws Exception {
        return body(mvc.perform(auth(get("/api/v1/invoices/" + id), token)).andExpect(status().isOk()));
    }

    private static void assertMoney(JsonNode actual, String expected) {
        assertThat(actual.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
