package com.propertypro.security;

import com.propertypro.common.RateLimitedException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AttemptLimiterTest {

    /** A clock the test moves by hand. */
    static class TestClock extends Clock {
        Instant now = Instant.parse("2026-10-27T08:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    final TestClock clock = new TestClock();
    final AttemptLimiter limiter = new AttemptLimiter(clock);
    final AuthRateLimits limits = new AuthRateLimits(limiter);

    @Test
    void blocksOnceTheLimitIsReachedAndFreesWhenTheOldestAttemptLeavesTheWindow() {
        Duration window = Duration.ofMinutes(15);
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.blockedFor("k", 3, window)).isEmpty();
            limiter.record("k");
            clock.advance(Duration.ofMinutes(1));
        }
        // Attempts at 0, 1 and 2 minutes; now is 3 minutes: free again at 15
        assertThat(limiter.blockedFor("k", 3, window)).contains(Duration.ofMinutes(12));

        clock.advance(Duration.ofMinutes(12));
        assertThat(limiter.blockedFor("k", 3, window)).isEmpty();
    }

    @Test
    void tryAcquireCountsOnlyAllowedAttempts() {
        Duration hour = Duration.ofHours(1);
        assertThat(limiter.tryAcquire("s", 2, hour)).isEmpty();
        assertThat(limiter.tryAcquire("s", 2, hour)).isEmpty();
        assertThat(limiter.tryAcquire("s", 2, hour)).isPresent();
        assertThat(limiter.tryAcquire("s", 2, hour)).isPresent();

        // Refused attempts were not recorded, so the wait does not grow
        clock.advance(hour);
        assertThat(limiter.tryAcquire("s", 2, hour)).isEmpty();
    }

    @Test
    void fiveWrongPasswordsLockThatEmailFromThatIpOnly() {
        for (int i = 0; i < AuthRateLimits.LOGIN_PER_EMAIL_AND_IP; i++) {
            limits.checkLogin("Mary@Acacia.co.ke", "1.1.1.1");
            limits.loginFailed("mary@acacia.co.ke ", "1.1.1.1");
        }
        assertThatThrownBy(() -> limits.checkLogin("mary@acacia.co.ke", "1.1.1.1"))
                .isInstanceOfSatisfying(RateLimitedException.class, e -> {
                    assertThat(e.getCode()).isEqualTo("TOO_MANY_ATTEMPTS");
                    assertThat(e.getRetryAfterSeconds()).isEqualTo(15 * 60);
                });
        // The real user on another network can still sign in, and other emails are unaffected
        assertThatCode(() -> limits.checkLogin("mary@acacia.co.ke", "2.2.2.2")).doesNotThrowAnyException();
        assertThatCode(() -> limits.checkLogin("peter@acacia.co.ke", "1.1.1.1")).doesNotThrowAnyException();

        clock.advance(Duration.ofMinutes(15));
        assertThatCode(() -> limits.checkLogin("mary@acacia.co.ke", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void guessingFromManyIpsLocksTheEmail() {
        for (int i = 0; i < AuthRateLimits.LOGIN_PER_EMAIL; i++) {
            limits.loginFailed("mary@acacia.co.ke", "10.0.0." + i);
        }
        assertThatThrownBy(() -> limits.checkLogin("mary@acacia.co.ke", "10.0.1.1"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void oneIpTryingManyEmailsIsBlocked() {
        for (int i = 0; i < AuthRateLimits.LOGIN_PER_IP; i++) {
            limits.loginFailed("user" + i + "@x.co.ke", "3.3.3.3");
        }
        assertThatThrownBy(() -> limits.checkLogin("someone-new@x.co.ke", "3.3.3.3"))
                .isInstanceOf(RateLimitedException.class);
    }

    @Test
    void successfulLoginClearsTheEmailCounters() {
        for (int i = 0; i < AuthRateLimits.LOGIN_PER_EMAIL_AND_IP - 1; i++) {
            limits.loginFailed("mary@acacia.co.ke", "1.1.1.1");
        }
        limits.loginSucceeded("mary@acacia.co.ke", "1.1.1.1");
        limits.loginFailed("mary@acacia.co.ke", "1.1.1.1");
        assertThatCode(() -> limits.checkLogin("mary@acacia.co.ke", "1.1.1.1")).doesNotThrowAnyException();
    }

    @Test
    void signupsArePerIp() {
        for (int i = 0; i < AuthRateLimits.SIGNUPS_PER_IP; i++) {
            limits.signup("4.4.4.4");
        }
        assertThatThrownBy(() -> limits.signup("4.4.4.4"))
                .isInstanceOfSatisfying(RateLimitedException.class,
                        e -> assertThat(e.getCode()).isEqualTo("TOO_MANY_SIGNUPS"));
        assertThatCode(() -> limits.signup("5.5.5.5")).doesNotThrowAnyException();
    }
}
