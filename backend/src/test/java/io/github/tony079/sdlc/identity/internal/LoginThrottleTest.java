package io.github.tony079.sdlc.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class LoginThrottleTest {

    private final MutableClock clock = new MutableClock();
    private final LoginThrottle throttle = new LoginThrottle(3, 5, Duration.ofMinutes(10), clock);

    @Test
    void blocksAnAccountAfterTheLimitAndReleasesAfterTheWindow() {
        for (int i = 0; i < 3; i++) {
            assertThat(throttle.blockedFor("acct", "ip")).isEmpty();
            throttle.recordFailure("acct", "ip");
        }
        assertThat(throttle.blockedFor("acct", "ip")).isPresent();
        clock.advance(Duration.ofMinutes(10).plusSeconds(1));
        assertThat(throttle.blockedFor("acct", "ip")).isEmpty();
    }

    @Test
    void blocksAClientAcrossDifferentAccounts() {
        for (int i = 0; i < 5; i++) {
            throttle.recordFailure("acct-" + i, "ip");
        }
        assertThat(throttle.blockedFor("fresh-account", "ip")).isPresent();
        assertThat(throttle.blockedFor("fresh-account", "other-ip")).isEmpty();
    }

    @Test
    void successClearsTheAccountCounter() {
        throttle.recordFailure("acct", "ip");
        throttle.recordFailure("acct", "ip");
        throttle.recordSuccess("acct");
        throttle.recordFailure("acct", "ip");
        assertThat(throttle.blockedFor("acct", "ip")).isEmpty();
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-11-02T00:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
