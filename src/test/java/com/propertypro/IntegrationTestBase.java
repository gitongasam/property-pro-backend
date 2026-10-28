package com.propertypro;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.propertypro.integration.MessageGateways.EmailGateway;
import com.propertypro.integration.MessageGateways.SmsGateway;
import com.propertypro.tenancy.SystemExecutor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One PostgreSQL database and one Spring context for every integration test class
 * (Spring caches the context because every subclass has the same configuration).
 * <p>
 * The database is a Testcontainers PostgreSQL (needs Docker), or, when {@code TEST_DB_URL}
 * is set, an existing database that is wiped and migrated from scratch at the start of the
 * run. Its name must end in {@code _test}, so a real database can never be wiped by mistake:
 * <pre>
 * TEST_DB_URL=jdbc:postgresql://localhost:5432/propertypros_test
 * TEST_DB_OWNER_USER / TEST_DB_OWNER_PASSWORD   (default postgres / password)
 * TEST_DB_APP_USER / TEST_DB_APP_PASSWORD       (default propertypro_app / change-me-app)
 * </pre>
 * <p>
 * Email and SMS go to {@link Outbox} instead of a provider, so tests can read them.
 * Each sign-up comes from its own IP, so the per-IP sign-up limit never trips across tests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(IntegrationTestBase.RecordingGateways.class)
public abstract class IntegrationTestBase {

    private static final String EXTERNAL_URL = System.getenv("TEST_DB_URL");

    private static final String URL;
    private static final String OWNER_USER;
    private static final String OWNER_PASSWORD;
    private static final String APP_USER;
    private static final String APP_PASSWORD;

    static {
        if (EXTERNAL_URL == null || EXTERNAL_URL.isBlank()) {
            PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16").withInitScript("db/test-init.sql");
            postgres.start();
            URL = postgres.getJdbcUrl();
            OWNER_USER = postgres.getUsername();
            OWNER_PASSWORD = postgres.getPassword();
            APP_USER = "propertypro_app";
            APP_PASSWORD = "app";
        } else {
            URL = EXTERNAL_URL;
            OWNER_USER = env("TEST_DB_OWNER_USER", "postgres");
            OWNER_PASSWORD = env("TEST_DB_OWNER_PASSWORD", "password");
            APP_USER = env("TEST_DB_APP_USER", "propertypro_app");
            APP_PASSWORD = env("TEST_DB_APP_PASSWORD", "change-me-app");
            wipe();
        }
    }

    /** Empties the external test database so Flyway builds it from V1, as in a fresh container. */
    private static void wipe() {
        String dbName = URL.replaceAll("\\?.*$", "").replaceAll("^.*/", "");
        if (!dbName.endsWith("_test")) {
            throw new IllegalStateException("TEST_DB_URL must point at a database whose name ends in _test, not " + dbName);
        }
        try (Connection c = DriverManager.getConnection(URL, OWNER_USER, OWNER_PASSWORD);
             Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            st.execute("GRANT USAGE ON SCHEMA public TO " + APP_USER);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the test database " + dbName, e);
        }
    }

    private static String env(String name, String fallback) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? fallback : v;
    }

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);
    protected static final String PASSWORD = "Str0ng-pass";

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        // The app runs as the restricted account, exactly as in production
        registry.add("spring.datasource.url", () -> URL);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        // Flyway migrates as the owner account
        registry.add("spring.flyway.url", () -> URL);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("app.jobs.enabled", () -> "false");
        registry.add("app.signup.phone-verification", () -> "false");
        // The app imports .env; never let a test reach a real SMS or mail provider from it
        registry.add("zettatel.user-id", () -> "");
        registry.add("zettatel.password", () -> "");
        registry.add("app.mail.host", () -> "");
        registry.add("app.admin.bootstrap-email", () -> "");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected SystemExecutor system;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Outbox outbox;

    /** A new organisation; returns the sign-up response (accessToken, refreshToken, user...). */
    protected JsonNode signup(String org, String email, String phone) throws Exception {
        return body(mvc.perform(post("/api/v1/auth/signup")
                        .with(r -> { r.setRemoteAddr(nextIp()); return r; })
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "organizationName", org,
                                "firstName", "Test",
                                "email", email,
                                "phone", phone,
                                "password", PASSWORD))))
                .andExpect(status().isCreated()));
    }

    /** A unique Kenyan mobile number, so tests never collide on the unique phone index. */
    protected static String uniquePhone() {
        return "+2547" + String.format("%08d", Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000_000));
    }

    protected static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.co.ke";
    }

    protected static String nextIp() {
        int n = NEXT_IP.getAndIncrement();
        return "10.9." + (n / 250) + "." + (n % 250 + 1);
    }

    protected MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder authJson(MockHttpServletRequestBuilder request, String token, Object body)
            throws Exception {
        return auth(request, token).contentType(MediaType.APPLICATION_JSON)
                .content(body instanceof String s ? s : json.writeValueAsString(body));
    }

    protected JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }

    protected static UUID orgId(JsonNode signup) {
        return UUID.fromString(signup.at("/user/organization/id").asText());
    }

    // ---------------------------------------------------------------- messages

    public record Sent(String channel, String to, String subject, String text) {}

    /** Everything the app tried to email or text, newest last. */
    public static class Outbox {
        private final List<Sent> sent = new CopyOnWriteArrayList<>();

        public List<Sent> to(String recipient) {
            return sent.stream().filter(s -> s.to().equalsIgnoreCase(recipient)).toList();
        }

        void add(Sent s) {
            sent.add(s);
        }
    }

    @TestConfiguration
    static class RecordingGateways {
        @Bean
        Outbox outbox() {
            return new Outbox();
        }

        // @Primary: @ConditionalOnMissingBean on the logging defaults is not ordered against test config
        @Bean
        @Primary
        EmailGateway recordingEmailGateway(Outbox outbox) {
            return (to, subject, html) -> {
                outbox.add(new Sent("EMAIL", to, subject, html));
                return "test-" + UUID.randomUUID();
            };
        }

        @Bean
        @Primary
        SmsGateway recordingSmsGateway(Outbox outbox) {
            return (to, text) -> {
                outbox.add(new Sent("SMS", to, null, text));
                return "test-" + UUID.randomUUID();
            };
        }
    }
}
