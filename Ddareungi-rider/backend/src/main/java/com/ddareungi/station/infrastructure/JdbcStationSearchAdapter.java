package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationNameSearch;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.RowMapper;

@Profile("infra")
@Repository
public class JdbcStationSearchAdapter implements StationSearchPort {
    private static final String COLUMNS = "SELECT station_id, station_name, address1, address2, latitude, longitude, rack_total_count FROM station ";
    private static final RowMapper<Station> ROW = (rs, row) -> new Station(rs.getString("station_id"), rs.getString("station_name"),
            String.join(" ", java.util.stream.Stream.of(rs.getString("address1"), rs.getString("address2"))
                    .filter(value -> value != null && !value.isBlank()).toList()),
            new Location(rs.getDouble("latitude"), rs.getDouble("longitude")), rs.getInt("rack_total_count"), null, 0, null);
    private final JdbcTemplate jdbc;
    private final RedisRealtimeStationStore realtime;
    private volatile SearchCatalog searchCatalog;
    private record SearchCatalog(List<Station> stations, long loadedAt) {}
    public JdbcStationSearchAdapter(JdbcTemplate jdbc, RedisRealtimeStationStore realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    @Override
    public List<Station> findNearby(Location center, int radiusMeters) {
        double latDelta = Math.toDegrees(radiusMeters / 6_371_000.0);
        double lonDelta = Math.min(360, latDelta / Math.max(.000001, Math.cos(Math.toRadians(center.latitude()))));
        List<Station> candidates = jdbc.query(COLUMNS + "WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                ROW, center.latitude() - latDelta, center.latitude() + latDelta,
                center.longitude() - lonDelta, center.longitude() + lonDelta);
        return enrich(candidates, center).stream().filter(station -> station.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingDouble(Station::distanceMeters)).toList();
    }

    @Override
    public List<Station> findInBounds(StationBounds bounds) {
        var candidates = jdbc.query(COLUMNS + "WHERE latitude BETWEEN ? AND ? AND longitude BETWEEN ? AND ?",
                ROW, bounds.south(), bounds.north(), bounds.west(), bounds.east());
        return enrich(candidates, bounds.center()).stream().filter(station -> bounds.contains(station.location())).toList();
    }

    @Override
    public List<Station> findByIds(List<String> ids, Location center) {
        if (ids.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        List<Station> candidates = jdbc.query(COLUMNS + "WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND station_id IN (" + placeholders + ")", ROW, ids.toArray());
        return enrich(candidates, center);
    }

    @Override
    public List<Station> searchByName(String query, Location center) {
        return enrich(StationNameSearch.select(searchCatalog(), query, center), center);
    }

    // Only station metadata is cached. Always read live availability for the selected candidates.
    // A small local catalog avoids a full DB query on every autocomplete keystroke.
    private synchronized List<Station> searchCatalog() {
        long now = System.nanoTime();
        if (searchCatalog == null || now - searchCatalog.loadedAt() >= java.time.Duration.ofMinutes(1).toNanos()) {
            var stations = jdbc.query(COLUMNS + "WHERE latitude IS NOT NULL AND longitude IS NOT NULL", ROW);
            searchCatalog = new SearchCatalog(List.copyOf(stations), now);
        }
        return searchCatalog.stations();
    }

    private List<Station> enrich(List<Station> candidates, Location center) {
        Map<String, RealtimeStationStatus> statuses;
        try { statuses = realtime.findAll(candidates.stream().map(Station::stationId).toList()); }
        catch (DataAccessException exception) { statuses = Map.of(); }
        final var available = statuses;
        return candidates.stream().map(station -> {
            var status = available.get(station.stationId());
            var location = status == null ? station.location() : new Location(status.latitude(), status.longitude());
            return new Station(station.stationId(), status == null ? station.stationName() : status.stationName(),
                    station.address(), location, status == null ? station.rackTotalCount() : status.rackTotalCount(),
                    status == null ? null : status.availableBikeCount(), center.distanceTo(location),
                    status == null ? null : status.updatedAt());
        }).toList();
    }
}
