package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Login lockout, forgot / reset / change password, end to end. */
class AuthSecurityIntegrationTest extends IntegrationTestBase {

    private static final Pattern RESET_TOKEN = Pattern.compile("reset-password\\?token=([A-Za-z0-9_-]+)");

    @Test
    void repeatedWrongPasswordsLockTheAccountOnThatNetworkOnly() throws Exception {
        String email = uniqueEmail("lock");
        signup("Lockout Org", email, uniquePhone());
        String attacker = nextIp();

        for (int i = 0; i < 5; i++) {
            login(email, "wrong-password", attacker)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));
        }
        // Even the right password is refused from there now, before it is checked
        login(email, PASSWORD, attacker)
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"))
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER));

        // The owner on their own network is not locked out
        login(email, PASSWORD, nextIp()).andExpect(status().isOk());
        login(email, PASSWORD, attacker).andExpect(status().isTooManyRequests());
    }

    @Test
    void forgotPasswordSendsALinkThatWorksOnceAndSignsEverySessionOut() throws Exception {
        String email = uniqueEmail("reset");
        JsonNode account = signup("Reset Org", email, uniquePhone());
        String oldRefresh = account.get("refreshToken").asText();

        forgot(email).andExpect(status().isAccepted());
        String token = awaitResetToken(email);

        // Unknown emails get exactly the same answer and nothing is sent
        String nobody = uniqueEmail("nobody");
        forgot(nobody)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.message").value("If an account exists for that email, a reset link is on its way."));

        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", "N3w-password!"))))
                .andExpect(status().isNoContent());

        mvc.perform(post("/api/v1/auth/password/reset").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("token", token, "password", "An0ther-one!"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESET_INVALID"));

        login(email, PASSWORD, nextIp()).andExpect(status().isUnauthorized());
        login(email, "N3w-password!", nextIp()).andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("refreshToken", oldRefresh))))
                .andExpect(status().isUnauthorized());
        assertThat(resetEmails(nobody)).isEmpty();
    }

    @Test
    void secondResetRequestWithinAMinuteIsIgnored() throws Exception {
        String email = uniqueEmail("throttle");
        signup("Throttle Org", email, uniquePhone());

        forgot(email).andExpect(status().isAccepted());
        awaitResetToken(email);
        forgot(email).andExpect(status().isAccepted());

        Thread.sleep(500);   // delivery is asynchronous; give a wrongly sent second email time to show up
        assertThat(resetEmails(email)).hasSize(1);
    }

    @Test
    void changePasswordNeedsTheCurrentOneAndReturnsFreshTokens() throws Exception {
        String email = uniqueEmail("change");
        String token = signup("Change Org", email, uniquePhone()).get("accessToken").asText();

        mvc.perform(post("/api/v1/auth/password/change").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"x\",\"newPassword\":\"N3w-password!\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(authJson(post("/api/v1/auth/password/change"), token,
                        Map.of("currentPassword", "not-it", "newPassword", "N3w-password!")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("WRONG_PASSWORD"));

        mvc.perform(authJson(post("/api/v1/auth/password/change"), token,
                        Map.of("currentPassword", PASSWORD, "newPassword", PASSWORD)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SAME_PASSWORD"));

        JsonNode changed = body(mvc.perform(authJson(post("/api/v1/auth/password/change"), token,
                        Map.of("currentPassword", PASSWORD, "newPassword", "N3w-password!")))
                .andExpect(status().isOk()));
        mvc.perform(auth(get("/api/v1/auth/me"), changed.get("accessToken").asText()))
                .andExpect(status().isOk());

        login(email, PASSWORD, nextIp()).andExpect(status().isUnauthorized());
        login(email, "N3w-password!", nextIp()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- helpers

    private ResultActions login(String email, String password, String ip) throws Exception {
        return mvc.perform(post("/api/v1/auth/login")
                .with(r -> { r.setRemoteAddr(ip); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))));
    }

    private ResultActions forgot(String email) throws Exception {
        return mvc.perform(post("/api/v1/auth/password/forgot")
                .with(r -> { r.setRemoteAddr(nextIp()); return r; })
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email))));
    }

    private List<Sent> resetEmails(String email) {
        return outbox.to(email).stream().filter(s -> "Reset your PropertyPro password".equals(s.subject())).toList();
    }

    /** The reset email is sent off the request thread; wait for it. */
    private String awaitResetToken(String email) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            List<Sent> mails = resetEmails(email);
            if (!mails.isEmpty()) {
                Matcher m = RESET_TOKEN.matcher(mails.getLast().text());
                assertThat(m.find()).as("reset link in the email").isTrue();
                return m.group(1);
            }
            Thread.sleep(50);
        }
        throw new AssertionError("No reset email was sent to " + email);
    }
}
