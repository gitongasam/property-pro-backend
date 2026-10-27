package com.propertypro.config;

import com.propertypro.entity.User;
import com.propertypro.repository.UserRepository;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Locale;

/**
 * Creates the first super admin from {@code app.admin.bootstrap-*} at startup. There is no
 * public sign-up for platform users; further admins are added by an existing one.
 * Does nothing when the email is empty or already has an account (whatever its type).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final String ROLE = "SUPER_ADMIN";
    private static final int MIN_PASSWORD = 12;

    private final AppProperties props;
    private final SystemExecutor system;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JdbcClient jdbc;

    @Override
    public void run(ApplicationArguments args) {
        AppProperties.Admin cfg = props.admin();
        if (cfg == null || blank(cfg.bootstrapEmail()) || blank(cfg.bootstrapPassword())) {
            return;
        }
        String email = cfg.bootstrapEmail().trim().toLowerCase(Locale.ROOT);
        if (cfg.bootstrapPassword().length() < MIN_PASSWORD) {
            log.error("Not creating super admin {}: ADMIN_BOOTSTRAP_PASSWORD must be at least {} characters", email, MIN_PASSWORD);
            return;
        }
        String phone = blank(cfg.bootstrapPhone()) ? null : cfg.bootstrapPhone().trim();

        system.asSystem(() -> {
            if (users.emailTaken(email)) {
                log.info("Super admin bootstrap skipped: {} already has an account", email);
                return null;
            }
            if (phone != null && users.phoneTaken(phone)) {
                log.error("Not creating super admin {}: phone {} is used by another account", email, phone);
                return null;
            }
            User admin = new User();
            admin.setUserType(User.Type.PLATFORM);
            admin.setFirstName(blank(cfg.bootstrapFirstName()) ? "Platform Admin" : cfg.bootstrapFirstName().trim());
            admin.setEmail(email);
            admin.setPhone(phone);
            admin.setPasswordHash(passwordEncoder.encode(cfg.bootstrapPassword()));
            admin.setStatus(User.Status.ACTIVE);
            users.saveAndFlush(admin);
            int assigned = jdbc.sql("""
                            INSERT INTO user_roles (user_id, role_id)
                            SELECT ?, id FROM roles WHERE organization_id IS NULL AND code = ?""")
                    .param(admin.getId()).param(ROLE).update();
            if (assigned != 1) {
                throw new IllegalStateException("System role " + ROLE + " is missing");
            }
            log.warn("Created super admin {}. Remove ADMIN_BOOTSTRAP_PASSWORD from the environment now.", email);
            return null;
        });
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
