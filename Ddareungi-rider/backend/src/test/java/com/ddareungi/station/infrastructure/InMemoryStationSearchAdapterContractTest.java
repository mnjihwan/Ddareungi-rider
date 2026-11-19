package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** P2-1 port contract for the local sample adapter: every seed is public and FRESH at the evaluation instant. */
class InMemoryStationSearchAdapterContractTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private final InMemoryStationSearchAdapter adapter =
            new InMemoryStationSearchAdapter(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));

    @Test
    void findWithinReturnsFreshCandidatesByDistance() {
        List<StationCandidate> result = adapter.findWithin(new Location(37.4979, 127.0276), 1500);

        assertThat(result).extracting(StationCandidate::stationId).containsExactly("ST-001", "ST-003", "ST-002");
        assertThat(result).allSatisfy(candidate -> {
            assertThat(candidate.status()).isEqualTo(RealtimeFreshness.FRESH);
            assertThat(candidate.ageSeconds()).isZero();
            assertThat(candidate.station().updatedAt()).isEqualTo(NOW);
            assertThat(candidate.station().shared()).isNotNull();
        });
        assertThat(result.getFirst().station().shared()).isEqualTo(35.0);
        assertThat(adapter.findWithin(new Location(37.6, 126.9), 500)).isEmpty();
    }

    @Test
    void lookupKeepsFirstSeenOrderAndReportsUnknownIds() {
        var result = adapter.lookup(List.of("ST-002", "nope", "ST-002", "ST-001"));

        assertThat(result).extracting(StationLookup::stationId).containsExactly("ST-002", "nope", "ST-001");
        assertThat(result).extracting(StationLookup::outcome).containsExactly(
                StationLookup.Outcome.FOUND, StationLookup.Outcome.NOT_FOUND, StationLookup.Outcome.FOUND);
        assertThat(result.getFirst().candidate().station().distanceMeters()).isZero();
        assertThat(adapter.lookup(List.of())).isEmpty();
    }

    @Test
    void summaryCountsAllSeedsAsFresh() {
        var summary = adapter.realtimeSummary();
        assertThat(summary.total()).isEqualTo(3);
        assertThat(summary.fresh()).isEqualTo(3);
        assertThat(summary.lastSuccessAt()).isEqualTo(NOW);
    }
}
