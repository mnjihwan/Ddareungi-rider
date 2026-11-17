package com.ddareungi.station.application;

import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationCandidate;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** R4 grid aggregation: equal to an independent full computation, count-preserving, exact cell boundaries. */
class StationClustersTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private static final double[] CELLS = {0.003, 0.005, 0.01, 0.02, 0.0007, 0.25};

    private static StationCandidate candidate(String id, double lat, double lon, RealtimeFreshness status, Integer bikes) {
        Integer count = status == RealtimeFreshness.UNAVAILABLE ? null : bikes;
        Instant updatedAt = status == RealtimeFreshness.UNAVAILABLE ? null : NOW;
        return new StationCandidate(new Station(id, id, "서울", new Location(lat, lon), 10, count, 0, updatedAt),
                status, status == RealtimeFreshness.UNAVAILABLE ? null : 0L);
    }

    /**
     * Reference cell: the k with k*d <= x < (k+1)*d found by a linear walk (no floor/division shortcut), so the
     * comparison checks {@link StationClusters#cellIndex} instead of re-using it.
     */
    private static long referenceCell(double x, double d) {
        long k = (long) (x / d) - 3;
        while (!(k * d <= x && x < (k + 1) * d)) k++;
        return k;
    }

    private record Expected(double latSum, double lonSum, int count, int bikes, int fresh) {
        Expected add(StationCandidate c) {
            Integer b = c.station().availableBikeCount();
            return new Expected(latSum + c.station().location().latitude(), lonSum + c.station().location().longitude(),
                    count + 1, bikes + (b == null ? 0 : b), fresh + (c.status() == RealtimeFreshness.FRESH ? 1 : 0));
        }
    }

    @Test
    void aggregationEqualsAFullComputationForRandomStations() {
        Random random = new Random(20261117L);
        for (int round = 0; round < 300; round++) {
            double cell = CELLS[random.nextInt(CELLS.length)];
            // Seoul-like area most of the time, the whole globe (negative coordinates, extremes) sometimes.
            boolean global = round % 10 == 0;
            int size = random.nextInt(400);
            List<StationCandidate> candidates = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                double lat = global ? -90 + 180 * random.nextDouble() : 37.4 + 0.3 * random.nextDouble();
                double lon = global ? -180 + 360 * random.nextDouble() : 126.8 + 0.4 * random.nextDouble();
                if (random.nextInt(8) == 0) { // exactly on a grid line
                    lat = Math.max(-90, Math.min(90, Math.round(lat / cell) * cell));
                    lon = Math.max(-180, Math.min(180, Math.round(lon / cell) * cell));
                }
                RealtimeFreshness status = RealtimeFreshness.values()[random.nextInt(3)];
                candidates.add(candidate("S" + i, lat, lon, status, random.nextInt(40)));
            }

            var result = StationClusters.aggregate(candidates, cell);

            Map<List<Long>, Expected> expected = new TreeMap<>((a, b) -> a.get(0).equals(b.get(0))
                    ? Long.compare(a.get(1), b.get(1)) : Long.compare(a.get(0), b.get(0)));
            boolean stale = false;
            boolean unavailable = false;
            for (StationCandidate c : candidates) {
                if (c.status() == RealtimeFreshness.UNAVAILABLE) { unavailable = true; continue; }
                stale |= c.status() == RealtimeFreshness.STALE;
                var key = List.of(referenceCell(c.station().location().latitude(), cell),
                        referenceCell(c.station().location().longitude(), cell));
                expected.merge(key, new Expected(0, 0, 0, 0, 0).add(c), (old, ignored) -> old.add(c));
            }
            assertThat(result.anyStale()).isEqualTo(stale);
            assertThat(result.anyUnavailable()).isEqualTo(unavailable);
            assertThat(result.clusters()).hasSize(expected.size());
            int index = 0;
            for (var entry : expected.entrySet()) {
                var actual = result.clusters().get(index++);
                Expected e = entry.getValue();
                assertThat(actual.stationCount()).isEqualTo(e.count());
                assertThat(actual.parkingBikeTotCnt()).isEqualTo(e.bikes());
                assertThat(actual.freshCount()).isEqualTo(e.fresh());
                assertThat(actual.latitude()).isCloseTo(e.latSum() / e.count(), within(1e-9));
                assertThat(actual.longitude()).isCloseTo(e.lonSum() / e.count(), within(1e-9));
                // The mean of a cell's members stays in that cell (up to rounding).
                long latCell = entry.getKey().get(0), lonCell = entry.getKey().get(1);
                assertThat(actual.latitude()).isBetween(latCell * cell - 1e-9, (latCell + 1) * cell + 1e-9);
                assertThat(actual.longitude()).isBetween(lonCell * cell - 1e-9, (lonCell + 1) * cell + 1e-9);
            }
            // Count preservation: every FRESH/STALE candidate is in exactly one cluster.
            long servable = candidates.stream().filter(c -> c.status() != RealtimeFreshness.UNAVAILABLE).count();
            assertThat(result.stationTotal()).isEqualTo((int) servable);
        }
    }

    @Test
    void cellIndexHonoursHalfOpenCellsIncludingNegativeAndBoundaryValues() {
        Random random = new Random(7L);
        for (double cell : CELLS) {
            for (int i = 0; i < 20_000; i++) {
                double x = i % 2 == 0 ? -180 + 360 * random.nextDouble()
                        : Math.max(-180, Math.min(180, (random.nextInt(2_000_001) - 1_000_000) * cell));
                long k = StationClusters.cellIndex(x, cell);
                assertThat(k * cell <= x && x < (k + 1) * cell).as("x=%s cell=%s k=%s", x, cell, k).isTrue();
                assertThat(k).isEqualTo(referenceCell(x, cell));
            }
        }
        assertThat(StationClusters.cellIndex(0, 0.005)).isZero();
        assertThat(StationClusters.cellIndex(-1e-12, 0.005)).isEqualTo(-1);
    }

    @Test
    void unavailableIsLeftOutAndStaleIsSummedWithItsLastCount() {
        var result = StationClusters.aggregate(List.of(
                candidate("a", 37.50010, 127.00010, RealtimeFreshness.FRESH, 3),
                candidate("b", 37.50030, 127.00030, RealtimeFreshness.STALE, 4),
                candidate("c", 37.50020, 127.00020, RealtimeFreshness.UNAVAILABLE, null),
                candidate("d", 37.51010, 127.00010, RealtimeFreshness.FRESH, 0)), 0.005);

        assertThat(result.clusters()).hasSize(2);
        var first = result.clusters().getFirst();
        assertThat(first.stationCount()).isEqualTo(2);
        assertThat(first.parkingBikeTotCnt()).isEqualTo(7);
        assertThat(first.freshCount()).isEqualTo(1);
        assertThat(first.latitude()).isCloseTo(37.5002, within(1e-12));
        assertThat(first.longitude()).isCloseTo(127.0002, within(1e-12));
        var second = result.clusters().get(1);
        assertThat(second.stationCount()).isEqualTo(1);
        assertThat(second.parkingBikeTotCnt()).isZero(); // FRESH 0 is a real 0
        assertThat(second.latitude()).isEqualTo(37.5101); // a one-station cluster sits on the station
        assertThat(result.anyStale()).isTrue();
        assertThat(result.anyUnavailable()).isTrue();
        assertThat(result.stationTotal()).isEqualTo(3);
    }

    @Test
    void emptyInputGivesNoClusters() {
        var result = StationClusters.aggregate(List.of(), 0.01);
        assertThat(result.clusters()).isEmpty();
        assertThat(result.anyStale()).isFalse();
        assertThat(result.anyUnavailable()).isFalse();
    }
}
