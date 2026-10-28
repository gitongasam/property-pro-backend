package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Water readings from the first (opening) reading to the invoice line: the tenant pays only
 * for water used after moving in, at the lease's rate, and a reading is billed once.
 * Dates are last month's, so they are always in the past and next month's billing is due today.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MeterReadingIntegrationTest extends IntegrationTestBase {

    static final LocalDate MOVE_IN = LocalDate.now(ZoneId.of("Africa/Nairobi")).withDayOfMonth(1).minusMonths(1);

    String token;
    String otherOrgToken;
    String propertyId;
    String unitA1;
    String unitA2;
    String tenantId;
    String midMonthReadingId;

    @BeforeAll
    void setUp() throws Exception {
        token = signup("Meter Org", uniqueEmail("meter"), uniquePhone()).get("accessToken").asText();
        otherOrgToken = signup("Other Meter Org", uniqueEmail("meter-other"), uniquePhone()).get("accessToken").asText();

        propertyId = body(mvc.perform(authJson(post("/api/v1/properties"), token, """
                        {"code":"MTR","name":"Meter Court","propertyType":"RESIDENTIAL"}"""))
                .andExpect(status().isCreated())).get("id").asText();
        JsonNode units = body(mvc.perform(authJson(post("/api/v1/properties/" + propertyId + "/units/bulk"), token, """
                        {"units":[{"unitNo":"A1","unitType":"ONE_BR","marketRent":20000},
                                  {"unitNo":"A2","unitType":"ONE_BR","marketRent":20000}]}"""))
                .andExpect(status().isCreated()));
        unitA1 = units.get(0).get("id").asText();
        unitA2 = units.get(1).get("id").asText();
        tenantId = body(mvc.perform(authJson(post("/api/v1/tenants"), token, Map.of(
                        "tenantType", "INDIVIDUAL", "name", "Otieno Water", "phone", uniquePhone())))
                .andExpect(status().isCreated())).get("id").asText();
    }

    @Test
    @Order(1)
    void readingsBeforeMoveInBelongToNobody() throws Exception {
        // While A1 is vacant: the opening reading, then 50 units used before the tenant arrives
        JsonNode opening = body(record(unitA1, MOVE_IN.minusDays(10), "100", null).andExpect(status().isCreated()));
        assertMoney(opening.get("previousReading"), "100");
        assertMoney(opening.get("consumption"), "0");

        record(unitA1, MOVE_IN, "150", null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.consumption").value(50))
                .andExpect(jsonPath("$.billable").value(false));
    }

    @Test
    @Order(2)
    void tenantMovesInWithMeteredWater() throws Exception {
        Map<String, Object> lease = new LinkedHashMap<>();
        lease.put("unitId", unitA1);
        lease.put("tenantId", tenantId);
        lease.put("startDate", MOVE_IN.toString());
        lease.put("rentAmount", 20000);
        lease.put("billingFrequency", "MONTHLY");
        lease.put("billingDay", 1);
        lease.put("dueDays", 5);
        lease.put("charges", List.of(Map.of("chargeTypeCode", "WATER", "amount", 0, "metered", true, "ratePerUnit", 150)));
        String leaseId = body(mvc.perform(authJson(post("/api/v1/leases"), token, lease))
                .andExpect(status().isCreated())).get("id").asText();
        mvc.perform(auth(post("/api/v1/leases/" + leaseId + "/activate"), token)).andExpect(status().isOk());

        JsonNode mid = body(record(unitA1, MOVE_IN.plusDays(15), "180", null).andExpect(status().isCreated()));
        midMonthReadingId = mid.get("id").asText();
        assertMoney(mid.get("previousReading"), "150");    // chained onto the last reading
        assertMoney(mid.get("consumption"), "30");
        assertThat(mid.get("billable").asBoolean()).isTrue();
        assertThat(mid.get("billed").asBoolean()).isFalse();
    }

    @Test
    @Order(3)
    void mistakesAreRefused() throws Exception {
        record(unitA1, MOVE_IN.plusDays(16), "170", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_BELOW_PREVIOUS"));
        record(unitA1, MOVE_IN.plusDays(15), "200", null)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_EXISTS"));
        record(unitA1, MOVE_IN.plusDays(5), "200", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_OUT_OF_ORDER"));
        record(unitA1, LocalDate.now(ZoneId.of("Africa/Nairobi")).plusDays(1), "200", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_IN_FUTURE"));
    }

    @Test
    @Order(4)
    void aBadRoundSavesNothing() throws Exception {
        mvc.perform(authJson(post("/api/v1/meter-readings/bulk"), token, Map.of(
                        "utility", "WATER", "readingDate", MOVE_IN.plusDays(20).toString(),
                        "readings", List.of(
                                Map.of("unitId", unitA2, "currentReading", 10),
                                Map.of("unitId", unitA1, "currentReading", 1)))))   // below 180
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("READING_BELOW_PREVIOUS"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.startsWith("Unit A1:")));

        mvc.perform(auth(get("/api/v1/meter-readings").param("unitId", unitA2), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    @Order(5)
    void theLatestUnbilledReadingCanBeCorrected() throws Exception {
        mvc.perform(authJson(put("/api/v1/meter-readings/" + midMonthReadingId), token, Map.of("currentReading", 175)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumption").value(25));

        // An earlier reading cannot change: the later ones are built on it
        String opening = body(mvc.perform(auth(get("/api/v1/meter-readings")
                        .param("unitId", unitA1).param("to", MOVE_IN.minusDays(10).toString()), token))
                .andExpect(status().isOk())).at("/content/0/id").asText();
        mvc.perform(authJson(put("/api/v1/meter-readings/" + opening), token, Map.of("currentReading", 90)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_NOT_LATEST"));
    }

    @Test
    @Order(6)
    void sheetShowsEveryUnitWithItsLastReading() throws Exception {
        JsonNode sheet = body(mvc.perform(auth(get("/api/v1/meter-readings/sheet")
                        .param("propertyId", propertyId).param("utility", "WATER"), token))
                .andExpect(status().isOk()));
        assertThat(sheet).hasSize(2);
        JsonNode a1 = sheet.get(0);
        assertThat(a1.get("unitNo").asText()).isEqualTo("A1");
        assertThat(a1.get("tenantName").asText()).isEqualTo("Otieno Water");
        assertThat(a1.get("billable").asBoolean()).isTrue();
        assertMoney(a1.get("ratePerUnit"), "150");
        assertMoney(a1.get("lastReading"), "175");
        JsonNode a2 = sheet.get(1);
        assertThat(a2.get("billable").asBoolean()).isFalse();
        assertThat(a2.get("lastReading").isNull()).isTrue();
    }

    @Test
    @Order(7)
    void nextInvoiceChargesOnlyTheTenantsWaterAndOnlyOnce() throws Exception {
        LocalDate billingDate = MOVE_IN.plusMonths(1);
        mvc.perform(auth(post("/api/v1/invoices/run-billing").param("date", billingDate.toString()), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.invoicesCreated").value(1));

        JsonNode page = body(mvc.perform(auth(get("/api/v1/invoices").param("tenantId", tenantId), token))
                .andExpect(status().isOk()));
        String scheduledId = null;
        for (JsonNode i : page.get("content")) {
            if ("SCHEDULED".equals(i.get("source").asText())) scheduledId = i.get("id").asText();
        }
        assertThat(scheduledId).as("the monthly invoice").isNotNull();
        JsonNode invoice = body(mvc.perform(auth(get("/api/v1/invoices/" + scheduledId), token))
                .andExpect(status().isOk()));
        // Rent 20,000 + 25 units x 150; the 50 units used before move-in are not charged
        assertMoney(invoice.get("total"), "23750");
        List<JsonNode> water = new java.util.ArrayList<>();
        invoice.get("lines").forEach(l -> { if ("WATER".equals(l.get("chargeTypeCode").asText())) water.add(l); });
        assertThat(water).hasSize(1);
        assertMoney(water.getFirst().get("quantity"), "25");

        mvc.perform(auth(get("/api/v1/meter-readings/" + midMonthReadingId), token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.billed").value(true))
                .andExpect(jsonPath("$.invoiceNo").value(invoice.get("invoiceNo").asText()));

        // Billed readings are locked
        mvc.perform(authJson(put("/api/v1/meter-readings/" + midMonthReadingId), token, Map.of("currentReading", 176)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("READING_BILLED"));
        mvc.perform(auth(delete("/api/v1/meter-readings/" + midMonthReadingId), token))
                .andExpect(status().isConflict());
    }

    @Test
    @Order(8)
    void anotherOrganisationCannotSeeOrRecordOnTheseMeters() throws Exception {
        mvc.perform(auth(get("/api/v1/meter-readings/" + midMonthReadingId), otherOrgToken))
                .andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/v1/meter-readings"), otherOrgToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
        mvc.perform(authJson(post("/api/v1/meter-readings"), otherOrgToken, Map.of(
                        "unitId", unitA2, "utility", "WATER", "currentReading", 5)))
                .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions record(String unitId, LocalDate date, String current, String previous) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("unitId", unitId);
        body.put("utility", "WATER");
        body.put("readingDate", date.toString());
        body.put("currentReading", new BigDecimal(current));
        if (previous != null) body.put("previousReading", new BigDecimal(previous));
        return mvc.perform(authJson(post("/api/v1/meter-readings"), token, body));
    }

    private static void assertMoney(JsonNode actual, String expected) {
        assertThat(actual.decimalValue()).isEqualByComparingTo(new BigDecimal(expected));
    }
}
