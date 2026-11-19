package com.ddareungi.global.ratelimit;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A test clock that only moves when told to. */
final class MutableClock extends Clock {
    private Instant now;

    MutableClock(Instant start) { this.now = start; }

    void advance(Duration duration) { now = now.plus(duration); }

    void set(Instant instant) { now = instant; }

    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return now; }
}
