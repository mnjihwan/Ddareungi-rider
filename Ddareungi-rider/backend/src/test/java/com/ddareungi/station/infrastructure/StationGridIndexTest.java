package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Property tests: grid index results equal a full scan for random coordinates, radii and boxes (fixed seeds). */
class StationGridIndexTest {
    private record Point(int id, Location location) {}

    private static List<Point> points(Random random, int count, double south, double north, double west, double east) {
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            points.add(new Point(i, new Location(south + random.nextDouble() * (north - south), west + random.nextDouble() * (east - west))));
        }
        return points;
    }

    @Test
    void radiusQueriesEqualFullScanAroundSeoul() {
        Random random = new Random(20261116);
        List<Point> points = points(random, 4000, 37.40, 37.72, 126.75, 127.20);
        var grid = new StationGridIndex<>(points, Point::location);
        for (int trial = 0; trial < 2000; trial++) {
            Location center = new Location(37.38 + random.nextDouble() * 0.36, 126.73 + random.nextDouble() * 0.49);
            double radius = trial % 10 == 0 ? random.nextDouble() * 20 : random.nextDouble() * 5500;
            // Boundary cases: a radius exactly equal to some point's distance must include it.
            if (trial % 7 == 0) radius = center.distanceTo(points.get(random.nextInt(points.size())).location());
            double r = radius;
            List<Point> expected = points.stream().filter(p -> center.distanceTo(p.location()) <= r).toList();
            assertThat(grid.withinRadius(center, radius)).as("center %s radius %s", center, radius).isEqualTo(expected);
        }
    }

    @Test
    void radiusQueriesEqualFullScanWorldwideIncludingPolesAndAntimeridian() {
        Random random = new Random(42);
        List<Point> points = points(random, 3000, -90, 90, -180, 180);
        // Dense clusters near the antimeridian and a pole.
        points = new ArrayList<>(points);
        for (int i = 0; i < 300; i++) points.add(new Point(3000 + i, new Location(-1 + random.nextDouble() * 2, 179.95 + random.nextDouble() * 0.05)));
        for (int i = 0; i < 300; i++) points.add(new Point(3300 + i, new Location(89.95 + random.nextDouble() * 0.05, -180 + random.nextDouble() * 360)));
        var grid = new StationGridIndex<>(points, Point::location);
        List<Point> all = points;
        for (int trial = 0; trial < 1500; trial++) {
            Location center = trial % 3 == 0 ? all.get(random.nextInt(all.size())).location()
                    : new Location(-90 + random.nextDouble() * 180, -180 + random.nextDouble() * 360);
            double radius = trial % 5 == 0 ? random.nextDouble() * 3_000_000 : random.nextDouble() * 50_000;
            List<Point> expected = all.stream().filter(p -> center.distanceTo(p.location()) <= radius).toList();
            assertThat(grid.withinRadius(center, radius)).as("center %s radius %s", center, radius).isEqualTo(expected);
        }
    }

    @Test
    void boxQueriesEqualFullScan() {
        Random random = new Random(7);
        List<Point> points = points(random, 4000, 37.40, 37.72, 126.75, 127.20);
        var grid = new StationGridIndex<>(points, Point::location);
        for (int trial = 0; trial < 2000; trial++) {
            double south = 37.38 + random.nextDouble() * 0.36, west = 126.73 + random.nextDouble() * 0.49;
            double north = south + random.nextDouble() * (trial % 4 == 0 ? 1.0 : 0.05);
            double east = west + random.nextDouble() * (trial % 4 == 0 ? 1.0 : 0.05);
            List<Point> expected = points.stream().filter(p -> p.location().latitude() >= south && p.location().latitude() <= north
                    && p.location().longitude() >= west && p.location().longitude() <= east).toList();
            assertThat(grid.inBox(south, north, west, east)).isEqualTo(expected);
        }
    }

    @Test
    void emptyAndNegativeInputs() {
        var empty = new StationGridIndex<Point>(List.of(), Point::location);
        assertThat(empty.withinRadius(new Location(37.5, 127), 1000)).isEmpty();
        var one = new StationGridIndex<>(List.of(new Point(1, new Location(37.5, 127))), Point::location);
        assertThat(one.withinRadius(new Location(37.5, 127), -1)).isEmpty();
        assertThat(one.withinRadius(new Location(37.5, 127), 0)).hasSize(1);
    }

    /** End to end through the adapter: findNearby/findWithin/findInBounds equal a brute-force computation. */
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void adapterSpatialQueriesEqualBruteForce() {
        Random random = new Random(99);
        List<StationCatalog.Row> rows = new ArrayList<>();
        for (int i = 0; i < 3000; i++) {
            rows.add(new StationCatalog.Row("ST-" + i, "대여소 " + i, "서울", 37.40 + random.nextDouble() * 0.32,
                    126.75 + random.nextDouble() * 0.45, 10));
        }
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn((List) rows);
        var adapter = new JdbcStationSearchAdapter(jdbc, now -> CachedRealtimeSnapshot.empty(), RealtimeFreshnessPolicy.defaults());
        adapter.reloadCatalog();
        Comparator<Station> order = Comparator.comparingDouble(Station::distanceMeters).thenComparing(Station::stationId);
        for (int trial = 0; trial < 300; trial++) {
            Location center = new Location(37.40 + random.nextDouble() * 0.32, 126.75 + random.nextDouble() * 0.45);
            int radius = 100 + random.nextInt(4901);
            List<String> expected = rows.stream()
                    .map(row -> new Station(row.stationId(), row.stationName(), row.address(), new Location(row.latitude(), row.longitude()),
                            10, null, center.distanceTo(new Location(row.latitude(), row.longitude())), null))
                    .filter(station -> station.distanceMeters() <= radius).sorted(order).map(Station::stationId).toList();
            assertThat(adapter.findNearby(center, radius)).extracting(Station::stationId).isEqualTo(expected);
            assertThat(adapter.findWithin(center, radius)).extracting(c -> c.stationId()).isEqualTo(expected);

            double south = center.latitude() - random.nextDouble() * 0.05, west = center.longitude() - random.nextDouble() * 0.05;
            var bounds = new StationBounds(south, center.latitude() + 0.001, west, center.longitude() + 0.001);
            List<String> inBox = rows.stream().filter(row -> bounds.contains(new Location(row.latitude(), row.longitude())))
                    .map(StationCatalog.Row::stationId).sorted().toList();
            assertThat(adapter.findInBounds(bounds)).extracting(Station::stationId).containsExactlyInAnyOrderElementsOf(inBox);
        }
    }
}
