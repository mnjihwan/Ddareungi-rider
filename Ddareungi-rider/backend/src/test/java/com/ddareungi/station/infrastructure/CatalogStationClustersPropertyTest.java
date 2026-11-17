package com.ddareungi.station.infrastructure;

import com.ddareungi.global.response.WarningCode;
import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.station.application.StationClusters;
import com.ddareungi.station.application.StationMapProperties;
import com.ddareungi.station.application.StationMapService;
import com.ddareungi.station.application.StationViewMapper;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * R4 end to end over the in-memory catalog (grid index + snapshot join) with random Seoul-like data: the cluster
 * response equals a full computation over every catalog row (no grid), the cluster station total equals the
 * viewport item count for the same box, and the candidate port method returns exactly the viewport set.
 */
class CatalogStationClustersPropertyTest {
    private static final Instant NOW = Instant.parse("2026-11-17T03:00:00Z");
    private final RealtimeFreshnessPolicy policy = RealtimeFreshnessPolicy.defaults(Clock.fixed(NOW, ZoneOffset.UTC));

    /** What the generator decided per row, used by the full computation. */
    private record Truth(String id, boolean publicRow, boolean excluded, RealtimeFreshness status,
                         double latitude, double longitude, Integer bikes) {}

    @Test
    void clustersEqualAFullComputationAndPreserveTheViewportCount() {
        Random random = new Random(20261117L);
        for (int dataset = 0; dataset < 5; dataset++) {
            List<StationCatalog.Row> rows = new ArrayList<>();
            Map<String, RealtimeStationStatus> statuses = new HashMap<>();
            Set<String> excluded = new HashSet<>();
            List<Truth> truths = new ArrayList<>();
            for (int i = 0; i < 3000; i++) {
                String id = "ST-" + dataset + "-" + i;
                double lat = 37.40 + 0.32 * random.nextDouble();
                double lon = 126.75 + 0.45 * random.nextDouble();
                boolean publicRow = random.nextInt(20) != 0;
                rows.add(new StationCatalog.Row(id, publicRow ? "대여소 " + i : null, "서울", lat, lon, 10));
                boolean isExcluded = random.nextInt(25) == 0;
                if (isExcluded) excluded.add(id);
                int kind = random.nextInt(10); // 0-5 FRESH, 6-7 STALE, 8 aged out, 9 no value
                RealtimeFreshness status = kind <= 5 ? RealtimeFreshness.FRESH
                        : kind <= 7 ? RealtimeFreshness.STALE : RealtimeFreshness.UNAVAILABLE;
                double joinedLat = lat, joinedLon = lon;
                Integer bikes = null;
                if (kind <= 8) {
                    long age = kind <= 5 ? random.nextInt(121) : kind <= 7 ? 121 + random.nextInt(480) : 601 + random.nextInt(600);
                    int count = random.nextInt(30);
                    // Realtime coordinates may move a little (still inside the 500 m candidate margin).
                    double rtLat = lat + (random.nextDouble() - 0.5) * 0.002;
                    double rtLon = lon + (random.nextDouble() - 0.5) * 0.002;
                    statuses.put(id, new RealtimeStationStatus(id, "실시간 " + i, 10, count, 10.0, rtLat, rtLon,
                            NOW.minusSeconds(age)));
                    if (status != RealtimeFreshness.UNAVAILABLE) {
                        joinedLat = rtLat;
                        joinedLon = rtLon;
                        bikes = count;
                    }
                }
                truths.add(new Truth(id, publicRow, isExcluded, status, joinedLat, joinedLon, bikes));
            }
            var holder = new StationCatalogHolder(() -> rows, Clock.fixed(NOW, ZoneOffset.UTC));
            assertThat(holder.reload()).isTrue();
            var snapshot = new CachedRealtimeSnapshot(new RealtimeSnapshot(statuses, excluded), NOW, NOW);
            CatalogStationSearch adapter = new CatalogStationSearch(holder, now -> snapshot, policy) {};
            var service = new StationMapService(adapter, new StationViewMapper(policy),
                    new StationMapProperties(Integer.MAX_VALUE, List.of(0.003, 0.005, 0.01, 0.02)));

            for (int trial = 0; trial < 40; trial++) {
                double south = 37.38 + 0.3 * random.nextDouble();
                double west = 126.73 + 0.4 * random.nextDouble();
                var bounds = new StationBounds(south, south + 0.005 + 0.2 * random.nextDouble(),
                        west, west + 0.005 + 0.25 * random.nextDouble());
                int level = 5 + random.nextInt(4);
                double cell = StationMapProperties.defaults().cellDegrees(level);

                List<StationCandidate> candidates = adapter.findCandidatesInBounds(bounds);
                List<Station> stations = adapter.findInBounds(bounds);
                assertThat(candidates).extracting(StationCandidate::stationId)
                        .containsExactlyElementsOf(stations.stream().map(Station::stationId).toList());

                var viewport = service.viewport(bounds);
                var clusters = service.clusters(bounds, level);
                int clusterTotal = clusters.items().stream().mapToInt(StationClusters.Cluster::stationCount).sum();
                assertThat(clusterTotal).as("cluster total == viewport items").isEqualTo(viewport.items().size());

                // Full computation over every generated row, without the grid index or the port.
                Map<List<Long>, double[]> expected = new HashMap<>(); // lat sum, lon sum, count, bikes, fresh
                boolean stale = false, unavailable = false;
                for (Truth truth : truths) {
                    if (!truth.publicRow() || truth.excluded() || !bounds.contains(new com.ddareungi.station.domain.Location(
                            truth.latitude(), truth.longitude()))) continue;
                    if (truth.status() == RealtimeFreshness.UNAVAILABLE) { unavailable = true; continue; }
                    stale |= truth.status() == RealtimeFreshness.STALE;
                    // cellIndex itself is checked against a linear-walk reference in StationClustersTest.
                    var key = List.of(StationClusters.cellIndex(truth.latitude(), cell),
                            StationClusters.cellIndex(truth.longitude(), cell));
                    double[] acc = expected.computeIfAbsent(key, k -> new double[5]);
                    acc[0] += truth.latitude();
                    acc[1] += truth.longitude();
                    acc[2]++;
                    acc[3] += truth.bikes();
                    if (truth.status() == RealtimeFreshness.FRESH) acc[4]++;
                }
                assertThat(clusters.items()).as("cells").hasSize(expected.size());
                int expectedTotal = 0;
                for (var cluster : clusters.items()) {
                    var key = List.of(StationClusters.cellIndex(cluster.latitude(), cell),
                            StationClusters.cellIndex(cluster.longitude(), cell));
                    double[] acc = expected.get(key);
                    assertThat(acc).as("cell %s", key).isNotNull();
                    assertThat(cluster.stationCount()).isEqualTo((int) acc[2]);
                    assertThat(cluster.parkingBikeTotCnt()).isEqualTo((int) acc[3]);
                    assertThat(cluster.freshCount()).isEqualTo((int) acc[4]);
                    assertThat(cluster.latitude()).isCloseTo(acc[0] / acc[2], within(1e-9));
                    assertThat(cluster.longitude()).isCloseTo(acc[1] / acc[2], within(1e-9));
                    expectedTotal += (int) acc[2];
                }
                assertThat(clusterTotal).isEqualTo(expectedTotal);
                var warnings = new ArrayList<WarningCode>();
                if (stale) warnings.add(WarningCode.STALE_DATA);
                if (unavailable) warnings.add(WarningCode.REALTIME_DATA_UNAVAILABLE);
                assertThat(clusters.warnings()).containsExactlyElementsOf(warnings);
                assertThat(clusters.partial()).isEqualTo(!warnings.isEmpty());
                assertThat(viewport.warnings()).as("viewport uses the same A3 rule").containsExactlyElementsOf(warnings);
            }
        }
    }
}
