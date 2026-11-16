package com.ddareungi.realtime.domain;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RealtimeFreshnessPolicyTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private final RealtimeFreshnessPolicy policy = RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest(name = "age {0} ms -> {1} ({2}s)")
    @CsvSource({
            "0, FRESH, 0",
            "120000, FRESH, 120",
            "120999, FRESH, 120",   // floor: 120.999 s is 120
            "121000, STALE, 121",
            "600000, STALE, 600",
            "600999, STALE, 600",
            "601000, UNAVAILABLE, 601",
            "86400000, UNAVAILABLE, 86400",
            "-1, FRESH, 0",          // slightly in the future clamps to 0
            "-30000, FRESH, 0",      // 30 s skew is tolerated
            "-30001, UNAVAILABLE, 0" // beyond the skew limit
    })
    void boundaries(long ageMillis, RealtimeFreshness expected, long expectedAge) {
        var assessment = policy.classify(NOW.minusMillis(ageMillis), NOW);
        assertThat(assessment.status()).isEqualTo(expected);
        assertThat(assessment.ageSeconds()).isEqualTo(expectedAge);
    }

    @Test
    void missingCollectionTimeIsUnavailableWithoutAge() {
        var assessment = policy.classify(null, NOW);
        assertThat(assessment.status()).isEqualTo(RealtimeFreshness.UNAVAILABLE);
        assertThat(assessment.ageSeconds()).isNull();
    }

    @Test
    void nowComesFromTheInjectedClockAndDefaultsAre120And600() {
        assertThat(policy.now()).isEqualTo(NOW);
        assertThat(policy.freshSeconds()).isEqualTo(120);
        assertThat(policy.maxStaleSeconds()).isEqualTo(600);
        assertThat(RealtimeFreshness.STALE.servable()).isTrue();
        assertThat(RealtimeFreshness.UNAVAILABLE.servable()).isFalse();
    }

    @Test
    void customThresholdsAreUsed() {
        var custom = new RealtimeFreshnessPolicy(Clock.fixed(NOW, ZoneOffset.UTC), 60, 300);
        assertThat(custom.classify(NOW.minusSeconds(61), NOW).status()).isEqualTo(RealtimeFreshness.STALE);
        assertThat(custom.classify(NOW.minusSeconds(301), NOW).status()).isEqualTo(RealtimeFreshness.UNAVAILABLE);
    }

    @Test
    void invalidThresholdsAreRejected() {
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new RealtimeFreshnessPolicy(clock, 0, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeFreshnessPolicy(clock, 600, 600)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeFreshnessPolicy(clock, 700, 600)).isInstanceOf(IllegalArgumentException.class);
    }
}
