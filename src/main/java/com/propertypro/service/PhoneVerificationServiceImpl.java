package com.propertypro.service;

import com.propertypro.common.ApiException;
import com.propertypro.dto.AuthDtos.CodeSentResponse;
import com.propertypro.integration.MessageGateways.SmsGateway;
import com.propertypro.tenancy.SystemExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Codes are sent straight to the SMS gateway, not through the notification queue,
 * so they arrive in seconds. Only a hash is stored. Limits: a code lives 10 minutes,
 * 5 wrong guesses kill it, one send a minute and 5 sends an hour per user.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PhoneVerificationServiceImpl implements PhoneVerificationService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration CODE_TTL = Duration.ofMinutes(10);
    private static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    private static final int MAX_SENDS_PER_HOUR = 5;
    private static final int MAX_ATTEMPTS = 5;

    private final SystemExecutor system;
    private final JdbcClient jdbc;
    private final SmsGateway sms;

    private record Account(String phone, OffsetDateTime verifiedAt) {}

    private record OpenCode(String codeHash, OffsetDateTime expiresAt, int attempts, int sendsInWindow,
                            OffsetDateTime windowStartedAt, OffsetDateTime lastSentAt) {}

    @Override
    public CodeSentResponse sendCode(UUID userId) {
        Account account = account(userId);
        if (account.phone() == null) {
            throw ApiException.badRequest("NO_PHONE", "Add a mobile number to your profile first");
        }
        if (account.verifiedAt() != null) {
            throw ApiException.conflict("PHONE_ALREADY_VERIFIED", "Your mobile number is already confirmed");
        }
        OffsetDateTime now = OffsetDateTime.now();
        OpenCode open = openCode(userId);
        if (open != null) {
            long wait = Duration.between(now, open.lastSentAt().plus(RESEND_AFTER)).toSeconds();
            if (wait > 0) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "CODE_SENT_RECENTLY",
                        "A code was just sent. You can ask for another in " + wait + " seconds.");
            }
            if (open.windowStartedAt().isAfter(now.minusHours(1)) && open.sendsInWindow() >= MAX_SENDS_PER_HOUR) {
                throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_CODES",
                        "Too many codes requested. Try again in an hour.");
            }
        }

        String code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        try {
            sms.send(account.phone(), "Your PropertyPro confirmation code is " + code
                    + ". It expires in " + CODE_TTL.toMinutes() + " minutes. Do not share it with anyone.");
        } catch (RuntimeException e) {
            log.warn("Confirmation code SMS to user {} failed: {}", userId, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "SMS_FAILED",
                    "We could not send the code by SMS. Please try again shortly.");
        }

        system.asSystem(() -> jdbc.sql("""
                        INSERT INTO phone_verification_codes (user_id, phone, code_hash, expires_at, sends_in_window)
                        VALUES (?, ?, ?, ?, 1)
                        ON CONFLICT (user_id) DO UPDATE
                           SET phone = excluded.phone, code_hash = excluded.code_hash, expires_at = excluded.expires_at,
                               attempts = 0, last_sent_at = now(),
                               sends_in_window = CASE WHEN phone_verification_codes.window_started_at > now() - interval '1 hour'
                                                      THEN phone_verification_codes.sends_in_window + 1 ELSE 1 END,
                               window_started_at = CASE WHEN phone_verification_codes.window_started_at > now() - interval '1 hour'
                                                        THEN phone_verification_codes.window_started_at ELSE now() END""")
                .param(userId).param(account.phone()).param(hash(userId, code)).param(now.plus(CODE_TTL))
                .update());
        return new CodeSentResponse(mask(account.phone()), CODE_TTL.toSeconds(), RESEND_AFTER.toSeconds());
    }

    @Override
    public void verify(UUID userId, String code) {
        Account account = account(userId);
        if (account.verifiedAt() != null) {
            return;                                   // already confirmed: nothing to do
        }
        OpenCode open = openCode(userId);
        if (open == null) {
            throw ApiException.badRequest("NO_CODE", "Request a confirmation code first");
        }
        if (open.expiresAt().isBefore(OffsetDateTime.now())) {
            throw ApiException.badRequest("CODE_EXPIRED", "This code has expired. Request a new one.");
        }
        if (open.attempts() >= MAX_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "TOO_MANY_ATTEMPTS",
                    "Too many wrong codes. Request a new one.");
        }
        boolean matches = MessageDigest.isEqual(hash(userId, code.trim()).getBytes(StandardCharsets.UTF_8),
                open.codeHash().getBytes(StandardCharsets.UTF_8));
        if (!matches) {
            system.asSystem(() -> jdbc.sql("UPDATE phone_verification_codes SET attempts = attempts + 1 WHERE user_id = ?")
                    .param(userId).update());
            int left = MAX_ATTEMPTS - open.attempts() - 1;
            throw ApiException.badRequest("CODE_INVALID", left > 0
                    ? "That code is not right. " + left + (left == 1 ? " try" : " tries") + " left."
                    : "That code is not right. Request a new one.");
        }
        system.asSystem(() -> {
            jdbc.sql("UPDATE users SET phone_verified_at = now() WHERE id = ?").param(userId).update();
            jdbc.sql("DELETE FROM phone_verification_codes WHERE user_id = ?").param(userId).update();
            return null;
        });
        log.info("User {} confirmed their mobile number", userId);
    }

    private Account account(UUID userId) {
        return system.asSystem(() -> jdbc.sql("SELECT phone, phone_verified_at FROM users WHERE id = ? AND deleted_at IS NULL")
                .param(userId)
                .query((rs, n) -> new Account(rs.getString(1), rs.getObject(2, OffsetDateTime.class)))
                .optional())
                .orElseThrow(() -> ApiException.unauthorized("Account not found"));
    }

    private OpenCode openCode(UUID userId) {
        return system.asSystem(() -> jdbc.sql("""
                        SELECT code_hash, expires_at, attempts, sends_in_window, window_started_at, last_sent_at
                          FROM phone_verification_codes WHERE user_id = ?""")
                .param(userId)
                .query((rs, n) -> new OpenCode(rs.getString(1), rs.getObject(2, OffsetDateTime.class), rs.getInt(3),
                        rs.getInt(4), rs.getObject(5, OffsetDateTime.class), rs.getObject(6, OffsetDateTime.class)))
                .optional().orElse(null));
    }

    private static String hash(UUID userId, String code) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((userId + ":" + code).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** +254712345678 -> +2547*** ***678 */
    private static String mask(String phone) {
        return phone.length() < 7 ? phone : phone.substring(0, 5) + "*** ***" + phone.substring(phone.length() - 3);
    }
}
