package com.propertypro.security;

import com.propertypro.common.RateLimitedException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Limits on the public auth endpoints, against password guessing and sign-up spam.
 * <p>
 * Failed logins are counted three ways: per email from one IP (the tight limit, so one
 * attacker cannot lock a real user out from everywhere), per email from any IP (slow,
 * spread-out guessing) and per IP across emails (trying one password on many accounts).
 * A successful login clears the per-email counters.
 * <p>
 * Behind a proxy (ngrok, a load balancer) every request has the proxy's IP unless
 * {@code server.forward-headers-strategy} is set, so the per-IP limits are generous.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthRateLimits {

    static final int LOGIN_PER_EMAIL_AND_IP = 5;
    static final Duration LOGIN_PER_EMAIL_AND_IP_WINDOW = Duration.ofMinutes(15);
    static final int LOGIN_PER_EMAIL = 20;
    static final Duration LOGIN_PER_EMAIL_WINDOW = Duration.ofHours(1);
    static final int LOGIN_PER_IP = 50;
    static final Duration LOGIN_PER_IP_WINDOW = Duration.ofMinutes(15);

    static final int SIGNUPS_PER_IP = 10;
    static final int FORGOT_PER_IP = 20;
    static final Duration HOUR = Duration.ofHours(1);

    private final AttemptLimiter limiter;

    /** Throws 429 when this email or IP has failed too often; call before checking the password. */
    public void checkLogin(String email, String ip) {
        Optional<Duration> blocked = longest(
                limiter.blockedFor(emailIpKey(email, ip), LOGIN_PER_EMAIL_AND_IP, LOGIN_PER_EMAIL_AND_IP_WINDOW),
                limiter.blockedFor(emailKey(email), LOGIN_PER_EMAIL, LOGIN_PER_EMAIL_WINDOW),
                limiter.blockedFor(ipKey("login", ip), LOGIN_PER_IP, LOGIN_PER_IP_WINDOW));
        if (blocked.isPresent()) {
            throw new RateLimitedException("TOO_MANY_ATTEMPTS",
                    "Too many failed sign-in attempts. Try again in " + RateLimitedException.waitText(blocked.get())
                            + ", or reset your password.", blocked.get());
        }
    }

    public void loginFailed(String email, String ip) {
        limiter.record(emailIpKey(email, ip));
        limiter.record(emailKey(email));
        limiter.record(ipKey("login", ip));
    }

    public void loginSucceeded(String email, String ip) {
        limiter.reset(emailIpKey(email, ip));
        limiter.reset(emailKey(email));
    }

    public void signup(String ip) {
        limiter.tryAcquire(ipKey("signup", ip), SIGNUPS_PER_IP, HOUR).ifPresent(wait -> {
            log.warn("Sign-up limit reached for IP {}", ip);
            throw new RateLimitedException("TOO_MANY_SIGNUPS",
                    "Too many sign-ups from this network. Try again in " + RateLimitedException.waitText(wait) + ".", wait);
        });
    }

    public void forgotPassword(String ip) {
        limiter.tryAcquire(ipKey("forgot", ip), FORGOT_PER_IP, HOUR).ifPresent(wait -> {
            throw new RateLimitedException("TOO_MANY_REQUESTS",
                    "Too many reset requests from this network. Try again in " + RateLimitedException.waitText(wait) + ".", wait);
        });
    }

    @SafeVarargs
    private static Optional<Duration> longest(Optional<Duration>... waits) {
        Optional<Duration> result = Optional.empty();
        for (Optional<Duration> w : waits) {
            if (w.isPresent() && (result.isEmpty() || w.get().compareTo(result.get()) > 0)) {
                result = w;
            }
        }
        return result;
    }

    private static String emailKey(String email) {
        return "login:email:" + normalise(email);
    }

    private static String emailIpKey(String email, String ip) {
        return "login:email-ip:" + normalise(email) + "|" + ip;
    }

    private static String ipKey(String action, String ip) {
        return action + ":ip:" + ip;
    }

    private static String normalise(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }
}
