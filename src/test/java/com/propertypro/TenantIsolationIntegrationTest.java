package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.http.MediaType;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end: sign-up, login, refresh rotation and, above all, that one
 * organisation can never see or touch another organisation's data.
 * Needs Docker (see IntegrationTestBase).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    String tokenA;
    String refreshA;
    UUID orgA;
    String tokenB;
    UUID orgB;
    String propertyA;

    @BeforeAll
    void signUpTwoOrganisations() throws Exception {
        JsonNode a = signup("Acacia Properties", "mary@acacia.co.ke", "+254711000001");
        tokenA = a.get("accessToken").asText();
        refreshA = a.get("refreshToken").asText();
        orgA = UUID.fromString(a.at("/user/organization/id").asText());

        JsonNode b = signup("Baobab Realty", "peter@baobab.co.ke", "+254711000002");
        tokenB = b.get("accessToken").asText();
        orgB = UUID.fromString(b.at("/user/organization/id").asText());
    }

    @Test
    @Order(1)
    void signupGivesOwnerRoleAndTrialPlan() throws Exception {
        mvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roles[0]").value("ORG_OWNER"))
                .andExpect(jsonPath("$.plan.code").value("GROWTH"))
                .andExpect(jsonPath("$.plan.subscriptionStatus").value("TRIALING"))
                .andExpect(jsonPath("$.organization.slug").value("acacia-properties"));
    }

    @Test
    @Order(2)
    void organisationACreatesPropertyAndUnits() throws Exception {
        JsonNode property = body(mvc.perform(post("/api/v1/properties")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"klm","name":"Kilimani Heights","propertyType":"RESIDENTIAL",
                                 "county":"Nairobi","town":"Nairobi","estate":"Kilimani"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("KLM")));
        propertyA = property.get("id").asText();

        mvc.perform(post("/api/v1/properties/" + propertyA + "/units/bulk")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"units":[
                                  {"unitNo":"B1","unitType":"ONE_BR","marketRent":18000,"depositAmount":18000},
                                  {"unitNo":"B2","unitType":"TWO_BR","marketRent":25000,"depositAmount":25000},
                                  {"unitNo":"Shop 1","unitType":"SHOP","marketRent":40000}
                                ]}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[2].paymentCode").value("KLM-SHOP1"));

        mvc.perform(get("/api/v1/properties/" + propertyA).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stats.totalUnits").value(3))
                .andExpect(jsonPath("$.stats.vacantUnits").value(3));
    }

    @Test
    @Order(3)
    void duplicateUnitNumberIsRejected() throws Exception {
        mvc.perform(post("/api/v1/properties/" + propertyA + "/units")
                        .header("Authorization", "Bearer " + tokenA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNo\":\"b1\",\"unitType\":\"ONE_BR\",\"marketRent\":18000}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("UNIT_NO_TAKEN"));
    }

    @Test
    @Order(4)
    void organisationBCannotSeeOrTouchOrganisationAData() throws Exception {
        mvc.perform(get("/api/v1/properties").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));

        mvc.perform(get("/api/v1/properties/" + propertyA).header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/properties/" + propertyA + "/units").header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/properties/" + propertyA + "/units")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"unitNo\":\"X1\",\"unitType\":\"SHOP\",\"marketRent\":1}"))
                .andExpect(status().isNotFound());

        // Property codes are unique per organisation, not globally
        mvc.perform(post("/api/v1/properties")
                        .header("Authorization", "Bearer " + tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"KLM\",\"name\":\"Kilimo Plaza\",\"propertyType\":\"COMMERCIAL\"}"))
                .andExpect(status().isCreated());
    }

    @Test
    @Order(5)
    void databaseIsolatesEvenWithoutHibernateFilter() {
        // Plain JDBC: no Hibernate tenant filter, only PostgreSQL Row-Level Security
        Long seenByA = system.asOrganization(orgA, null,
                () -> jdbc.queryForObject("SELECT count(*) FROM units", Long.class));
        Long seenByB = system.asOrganization(orgB, null,
                () -> jdbc.queryForObject("SELECT count(*) FROM units", Long.class));
        Long seenWithNoContext = jdbc.queryForObject("SELECT count(*) FROM units", Long.class);

        assertThat(seenByA).isEqualTo(3);
        assertThat(seenByB).isZero();
        assertThat(seenWithNoContext).isZero();
    }

    @Test
    @Order(6)
    void loginAndRefreshRotation() throws Exception {
        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"mary@acacia.co.ke\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"MARY@acacia.co.ke\",\"password\":\"Str0ng-pass\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").isNotEmpty());

        JsonNode rotated = body(mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isOk()));
        assertThat(rotated.get("refreshToken").asText()).isNotEqualTo(refreshA);

        // The old refresh token no longer works (and its reuse ends the user's sessions)
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshA + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + rotated.get("refreshToken").asText() + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    void anonymousRequestsAreRejectedAndPlansArePublic() throws Exception {
        mvc.perform(get("/api/v1/properties")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].code").value("STARTER"));
    }
}
