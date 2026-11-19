package com.ddareungi.station.application;

import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationSearchPort;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R4 viewport cap and cluster partial rules on top of the port. */
class StationMapServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final StationBounds BOUNDS = new StationBounds(37.4, 37.6, 126.9, 127.1);
    private final StationSearchPort port = mock(StationSearchPort.class);
    private final StationViewMapper mapper =
            new StationViewMapper(RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC)));
    private final StationMapService service = new StationMapService(port, mapper, StationMapProperties.defaults());

    /** Stations ordered as the port returns them (nearest first); updatedAt decides FRESH/STALE/UNAVAILABLE. */
    private static Station station(int i, Instant updatedAt) {
        return new Station("S" + i, "S" + i, "서울", new Location(37.5, 127.0), 10, updatedAt == null ? null : 1, i,
                updatedAt);
    }

    private static List<Station> fresh(int count) {
        return IntStream.range(0, count).mapToObj(i -> station(i, NOW)).toList();
    }

    @Test
    void viewportAtTheCapIsNotTruncated() {
        when(port.findInBounds(BOUNDS)).thenReturn(fresh(500));
        var view = service.viewport(BOUNDS);
        assertThat(view.items()).hasSize(500);
        assertThat(view.partial()).isFalse();
        assertThat(view.warnings()).isEmpty();
    }

    @Test
    void viewportAboveTheCapKeepsTheNearest500AndWarns() {
        when(port.findInBounds(BOUNDS)).thenReturn(fresh(1200));
        var view = service.viewport(BOUNDS);
        assertThat(view.items()).hasSize(500);
        assertThat(view.items()).extracting(item -> item.station().stationId())
                .containsExactlyElementsOf(IntStream.range(0, 500).mapToObj(i -> "S" + i).toList());
        assertThat(view.partial()).isTrue();
        assertThat(view.warnings()).containsExactly(WarningCode.RESULT_TRUNCATED);
        assertThat(view.meta().partial()).isTrue();
        assertThat(view.meta().warnings()).extracting(w -> w.code()).containsExactly("RESULT_TRUNCATED");
    }

    @Test
    void unavailableStationsDoNotCountTowardsTheCap() {
        List<Station> stations = new ArrayList<>(fresh(500));
        stations.add(10, station(9000, null));          // UNAVAILABLE among the nearest
        stations.add(station(9001, NOW.minusSeconds(700))); // aged beyond max-stale: UNAVAILABLE
        when(port.findInBounds(BOUNDS)).thenReturn(stations);
        var view = service.viewport(BOUNDS);
        assertThat(view.items()).hasSize(500);
        assertThat(view.warnings()).containsExactly(WarningCode.REALTIME_DATA_UNAVAILABLE);
    }

    @Test
    void staleDataWarningRefersToReturnedItemsOnlyAndWarningsKeepTheirOrder() {
        List<Station> stations = new ArrayList<>(fresh(500));
        stations.add(station(600, NOW.minusSeconds(300))); // STALE but beyond the cap
        stations.add(station(601, null));
        when(port.findInBounds(BOUNDS)).thenReturn(stations);
        assertThat(service.viewport(BOUNDS).warnings())
                .containsExactly(WarningCode.REALTIME_DATA_UNAVAILABLE, WarningCode.RESULT_TRUNCATED);

        stations.set(0, station(0, NOW.minusSeconds(300))); // STALE and returned
        assertThat(service.viewport(BOUNDS).warnings()).containsExactly(WarningCode.STALE_DATA,
                WarningCode.REALTIME_DATA_UNAVAILABLE, WarningCode.RESULT_TRUNCATED);
    }

    @Test
    void capIsConfigurable() {
        var small = new StationMapService(port, mapper, new StationMapProperties(2, List.of(0.003, 0.005, 0.01, 0.02)));
        when(port.findInBounds(BOUNDS)).thenReturn(fresh(3));
        var view = small.viewport(BOUNDS);
        assertThat(view.items()).extracting(item -> item.station().stationId()).containsExactly("S0", "S1");
        assertThat(view.warnings()).containsExactly(WarningCode.RESULT_TRUNCATED);
    }

    @Test
    void uncappedListBehaviourIsUnchanged() {
        when(port.findInBounds(BOUNDS)).thenReturn(fresh(3));
        var capped = service.viewport(BOUNDS);
        var plain = mapper.toListView(fresh(3));
        assertThat(capped).isEqualTo(plain);
    }

    private static StationCandidate candidate(String id, double lat, double lon, RealtimeFreshness status) {
        boolean servable = status.servable();
        return new StationCandidate(new Station(id, id, "서울", new Location(lat, lon), 10, servable ? 2 : null, 0,
                servable ? NOW : null), status, servable ? 0L : null);
    }

    @Test
    void clustersUseTheLevelCellAndTheA3PartialRule() {
        when(port.findCandidatesInBounds(BOUNDS)).thenReturn(List.of(
                candidate("a", 37.5001, 127.0001, RealtimeFreshness.FRESH),
                candidate("b", 37.5002, 127.0002, RealtimeFreshness.FRESH)));
        for (int level = 5; level <= 8; level++) {
            var view = service.clusters(BOUNDS, level);
            assertThat(view.level()).isEqualTo(level);
            assertThat(view.cellDegrees()).isEqualTo(StationMapProperties.defaults().clusterCellDegrees().get(level - 5));
            assertThat(view.items()).hasSize(1);
            assertThat(view.items().getFirst().stationCount()).isEqualTo(2);
            assertThat(view.partial()).isFalse();
            assertThat(view.meta().partial()).isNull();
        }

        when(port.findCandidatesInBounds(BOUNDS)).thenReturn(List.of(
                candidate("a", 37.5001, 127.0001, RealtimeFreshness.STALE),
                candidate("b", 37.5002, 127.0002, RealtimeFreshness.UNAVAILABLE)));
        var partial = service.clusters(BOUNDS, 5);
        assertThat(partial.items()).hasSize(1);
        assertThat(partial.items().getFirst().freshCount()).isZero();
        assertThat(partial.warnings()).containsExactly(WarningCode.STALE_DATA, WarningCode.REALTIME_DATA_UNAVAILABLE);

        when(port.findCandidatesInBounds(BOUNDS)).thenReturn(List.of(
                candidate("b", 37.5002, 127.0002, RealtimeFreshness.UNAVAILABLE)));
        var onlyUnavailable = service.clusters(BOUNDS, 8);
        assertThat(onlyUnavailable.items()).isEmpty();
        assertThat(onlyUnavailable.warnings()).containsExactly(WarningCode.REALTIME_DATA_UNAVAILABLE);

        when(port.findCandidatesInBounds(BOUNDS)).thenReturn(List.of());
        var empty = service.clusters(BOUNDS, 6);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.partial()).isFalse();
    }

    @Test
    void propertiesAreValidated() {
        assertThat(StationMapProperties.defaults().viewportMaxItems()).isEqualTo(500);
        assertThat(StationMapProperties.defaults().cellDegrees(5)).isEqualTo(0.003);
        assertThat(StationMapProperties.defaults().cellDegrees(8)).isEqualTo(0.02);
        assertThatThrownBy(() -> StationMapProperties.defaults().cellDegrees(4)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> StationMapProperties.defaults().cellDegrees(9)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new StationMapProperties(0, List.of(0.003, 0.005, 0.01, 0.02)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationMapProperties(500, List.of(0.003, 0.005, 0.01)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationMapProperties(500, List.of(0.003, 0.002, 0.01, 0.02)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationMapProperties(500, List.of(0.0, 0.005, 0.01, 0.02)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StationMapProperties(500, List.of(0.003, 0.005, 0.01, 1.5)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
