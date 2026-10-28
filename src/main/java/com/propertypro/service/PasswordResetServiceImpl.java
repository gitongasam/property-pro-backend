package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.config.AppProperties;
import com.propertypro.entity.User;
import com.propertypro.integration.MessageGateways.EmailGateway;
import com.propertypro.integration.MessageGateways.SmsGateway;
import com.propertypro.repository.UserRepository;
import com.propertypro.security.AuthRateLimits;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

/**
 * Messages go straight to the gateways, not the notification queue: platform admins have
 * no organisation to queue under, and a reset link should arrive in seconds.
 * Limits: one link a minute and 3 an hour per account; a new link cancels older ones.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PasswordResetServiceImpl implements PasswordResetService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration TOKEN_TTL = Duration.ofHours(1);
    private static final int MAX_PER_HOUR = 3;

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final EmailGateway email;
    private final SmsGateway sms;
    private final AppProperties props;
    private final AuthRateLimits rateLimits;

    private record Recent(long lastHour, OffsetDateTime latest) {}

    @Override
    public void requestReset(String rawEmail, String ipAddress) {
        rateLimits.forgotPassword(ipAddress);
        String address = rawEmail.trim();
        User user = system.asSystem(() -> users.findLoginCandidates(address).stream().findFirst().orElse(null));
        if (user == null) {
            log.info("Password reset requested for an email with no active account");
            return;
        }
        Recent recent = system.asSystem(() -> jdbc.sql("""
                        SELECT count(*) FILTER (WHERE created_at > now() - interval '1 hour'), max(created_at)
                          FROM password_reset_tokens WHERE user_id = ?""")
                .param(user.getId())
                .query((rs, n) -> new Recent(rs.getLong(1), rs.getObject(2, OffsetDateTime.class)))
                .single());
        if (recent.lastHour() >= MAX_PER_HOUR
                || (recent.latest() != null && recent.latest().isAfter(OffsetDateTime.now().minusMinutes(1)))) {
            log.warn("Password reset for user {} throttled", user.getId());
            return;
        }

        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        String raw = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        system.asSystem(() -> {
            jdbc.sql("UPDATE password_reset_tokens SET expires_at = now() WHERE user_id = ? AND used_at IS NULL AND expires_at > now()")
                    .param(user.getId()).update();
            jdbc.sql("""
                            INSERT INTO password_reset_tokens (user_id, token_hash, expires_at, requested_ip)
                            VALUES (?, ?, ?, ?::inet)""")
                    .param(user.getId()).param(AuthServiceImpl.sha256(raw))
                    .param(OffsetDateTime.now().plus(TOKEN_TTL)).param(ipAddress)
                    .update();
            return null;
        });

        String link = props.web().frontendUrl().replaceAll("/+$", "") + "/reset-password?token=" + raw;
        // Delivered off the request thread, so the response time does not reveal that the account exists
        Thread.ofVirtual().name("password-reset-" + user.getId()).start(() -> deliver(user, link));
    }

    private void deliver(User user, String link) {
        long minutes = TOKEN_TTL.toMinutes();
        List<String> sentBy = new ArrayList<>();
        try {
            email.send(user.getEmail(), "Reset your PropertyPro password",
                    "<p>Hi " + escape(user.getFirstName()) + ",</p>"
                            + "<p>We received a request to reset your PropertyPro password. This link works once and expires in "
                            + minutes + " minutes:</p>"
                            + "<p><a href=\"" + link + "\">Choose a new password</a></p>"
                            + "<p>If you did not ask for this, ignore this email; your password stays the same.</p>");
            sentBy.add("email");
        } catch (RuntimeException e) {
            log.error("Password reset email to user {} failed: {}", user.getId(), e.getMessage());
        }
        if (user.getPhone() != null && user.getPhoneVerifiedAt() != null) {
            try {
                sms.send(user.getPhone(), "PropertyPro: reset your password here (expires in " + minutes + " min): "
                        + link + " . If you did not ask for this, ignore it.");
                sentBy.add("SMS");
            } catch (RuntimeException e) {
                log.error("Password reset SMS to user {} failed: {}", user.getId(), e.getMessage());
            }
        }
        if (!sentBy.isEmpty()) {
            log.info("Password reset link sent to user {} by {}", user.getId(), String.join(" and ", sentBy));
        }
    }

    @Override
    public void resetPassword(String rawToken, String newPassword) {
        String hash = AuthServiceImpl.sha256(rawToken.trim());
        String passwordHash = passwordEncoder.encode(newPassword);
        UUID userId = system.asSystem(() -> {
            var rows = jdbc.sql("""
                            SELECT t.id, t.user_id FROM password_reset_tokens t
                              JOIN users u ON u.id = t.user_id
                             WHERE t.token_hash = ? AND t.used_at IS NULL AND t.expires_at > now()
                               AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
                               FOR UPDATE OF t""")
                    .param(hash)
                    .query((rs, n) -> new UUID[]{rs.getObject(1, UUID.class), rs.getObject(2, UUID.class)})
                    .list();
            if (rows.isEmpty()) {
                throw ApiException.badRequest("RESET_INVALID",
                        "This reset link has expired or was already used. Request a new one.");
            }
            UUID tokenId = rows.getFirst()[0];
            UUID uid = rows.getFirst()[1];
            jdbc.sql("UPDATE users SET password_hash = ? WHERE id = ?").param(passwordHash).param(uid).update();
            jdbc.sql("UPDATE password_reset_tokens SET used_at = now() WHERE id = ?").param(tokenId).update();
            jdbc.sql("UPDATE password_reset_tokens SET expires_at = now() WHERE user_id = ? AND used_at IS NULL AND expires_at > now()")
                    .param(uid).update();
            jdbc.sql("UPDATE refresh_tokens SET revoked_at = now() WHERE user_id = ? AND revoked_at IS NULL")
                    .param(uid).update();
            return uid;
        });
        log.info("User {} reset their password; all sessions signed out", userId);
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
