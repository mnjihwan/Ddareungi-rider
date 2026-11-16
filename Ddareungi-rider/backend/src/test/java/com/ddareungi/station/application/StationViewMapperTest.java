package com.ddareungi.station.application;

import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StationViewMapperTest {
    private static final Instant NOW = Instant.parse("2026-11-16T03:00:00Z");
    private final StationViewMapper mapper =
            new StationViewMapper(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));

    private static Station station(String id, Integer bikes, Instant updatedAt) {
        return new Station(id, id, "서울", new Location(37.5, 127.0), null, bikes, 10, updatedAt);
    }

    @Test
    void allFreshListIsNotPartial() {
        var view = mapper.toListView(List.of(station("a", 0, NOW.minusSeconds(120)), station("b", 3, NOW)));

        assertThat(view.items()).extracting(StationView::realtimeStatus)
                .containsExactly(RealtimeFreshness.FRESH, RealtimeFreshness.FRESH);
        assertThat(view.items().getFirst().station().availableBikeCount()).isZero(); // FRESH 0 is a real 0
        assertThat(view.partial()).isFalse();
        assertThat(view.warnings()).isEmpty();
        assertThat(view.meta().partial()).isNull();
        assertThat(view.meta().warnings()).isNull();
    }

    @Test
    void staleIsKeptWithCountsAndMakesTheListPartial() {
        var view = mapper.toListView(List.of(station("fresh", 2, NOW), station("stale", 5, NOW.minusSeconds(121))));

        assertThat(view.items()).extracting(item -> item.station().stationId()).containsExactly("fresh", "stale");
        assertThat(view.items().get(1).realtimeStatus()).isEqualTo(RealtimeFreshness.STALE);
        assertThat(view.items().get(1).realtimeAgeSeconds()).isEqualTo(121L);
        assertThat(view.items().get(1).station().availableBikeCount()).isEqualTo(5);
        assertThat(view.partial()).isTrue();
        assertThat(view.warnings()).containsExactly(WarningCode.STALE_DATA);
    }

    @Test
    void unavailableItemsAreRemovedAndReported() {
        var view = mapper.toListView(List.of(station("unknown", null, null), station("ok", 1, NOW),
                station("old", 4, NOW.minusSeconds(601)), station("future", 4, NOW.plusSeconds(31)),
                station("stale", 4, NOW.minusSeconds(600))));

        assertThat(view.items()).extracting(item -> item.station().stationId()).containsExactly("ok", "stale");
        assertThat(view.partial()).isTrue();
        assertThat(view.warnings()).containsExactly(WarningCode.STALE_DATA, WarningCode.REALTIME_DATA_UNAVAILABLE);
        assertThat(view.meta().partial()).isTrue();
        assertThat(view.meta().warnings()).extracting(w -> w.code())
                .containsExactly("STALE_DATA", "REALTIME_DATA_UNAVAILABLE");
    }

    @Test
    void emptyListIsNotPartial() {
        assertThat(mapper.toListView(List.of()).partial()).isFalse();
    }
}
