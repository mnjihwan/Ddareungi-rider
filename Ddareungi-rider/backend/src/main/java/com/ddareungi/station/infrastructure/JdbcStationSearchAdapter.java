package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.infrastructure.RedisRealtimeStationStore;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationNameSearch;
import com.ddareungi.station.domain.StationSearchPort;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * MySQL station metadata joined with Redis realtime values. Simple P2-1 implementation of the
 * {@link StationSearchPort} contract: every call reads Redis for its candidates; R3 replaces this with an in-memory
 * catalog, grid index and snapshot cache without changing the port semantics.
 */
@Profile("infra")
@Repository
public class JdbcStationSearchAdapter implements StationSearchPort {
    private static final String COLUMNS = "SELECT station_id, station_name, address1, address2, latitude, longitude, rack_total_count FROM station ";
    private static final RowMapper<Station> ROW = (rs, row) -> new Station(rs.getString("station_id"), rs.getString("station_name"),
            address(rs.getString("address1"), rs.getString("address2")),
            new Location(rs.getDouble("latitude"), rs.getDouble("longitude")),
            // NULL stays null (unknown), never 0.
            rs.getObject("rack_total_count", Integer.class), null, 0, null);
    /** Nullable view of a catalog row so lookup can tell "exists but invalid" (EXCLUDED) from "absent". */
    private static final RowMapper<CatalogRow> CATALOG_ROW = (rs, row) -> new CatalogRow(rs.getString("station_id"),
            rs.getString("station_name"), address(rs.getString("address1"), rs.getString("address2")),
            rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
            rs.getObject("rack_total_count", Integer.class));

    private final JdbcTemplate jdbc;
    private final RedisRealtimeStationStore realtime;
    private final RealtimeFreshnessPolicy policy;
    private volatile SearchCatalog searchCatalog;
    private record SearchCatalog(List<Station> stations, long loadedAt) {}

    private record CatalogRow(String stationId, @Nullable String stationName, String address,
                              @Nullable Double latitude, @Nullable Double longitude, @Nullable Integer rackTotalCount) {
        /** Public catalog requirements: non-blank name and valid coordinates. */
        Optional<Station> toStation() {
            if (stationName == null || stationName.isBlank() || latitude == null || longitude == null) return Optional.empty();
            try {
                return Optional.of(new Station(stationId, stationName, address, new Location(latitude, longitude),
                        rackTotalCount, null, 0, null));
            } catch (Location.InvalidCoordinateException exception) {
                return Optional.empty();
            }
        }
    }

    @Autowired
    public JdbcStationSearchAdapter(JdbcTemplate jdbc, RedisRealtimeStationStore realtime, RealtimeFreshnessPolicy policy) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.policy = policy;
    }

    /** Default 120/600 s policy (tests). */
    JdbcStationSearchAdapter(JdbcTemplate jdbc, RedisRealtimeStationStore realtime) {
        this(jdbc, realtime, RealtimeFreshnessPolicy.defaults());
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
        String placeholders = String.join(",", Collections.nCopies(ids.size(), "?"));
        List<Station> candidates = jdbc.query(COLUMNS + "WHERE latitude IS NOT NULL AND longitude IS NOT NULL AND station_id IN (" + placeholders + ")", ROW, ids.toArray());
        return enrich(candidates, center);
    }

    @Override
    public List<Station> searchByName(String query, Location center) {
        return enrich(StationNameSearch.select(searchCatalog(), query, center), center);
    }

    @Override
    public List<StationCandidate> findWithin(Location center, int radiusMeters) {
        Instant now = policy.now();
        return findNearby(center, radiusMeters).stream().map(station -> candidate(station, now))
                .sorted(Comparator.comparingDouble((StationCandidate c) -> c.station().distanceMeters())
                        .thenComparing(StationCandidate::stationId))
                .toList();
    }

    @Override
    public List<StationLookup> lookup(List<String> ids) {
        List<String> distinct = List.copyOf(new LinkedHashSet<>(ids));
        if (distinct.isEmpty()) return List.of();
        String placeholders = String.join(",", Collections.nCopies(distinct.size(), "?"));
        Map<String, CatalogRow> rows = jdbc.query(COLUMNS + "WHERE station_id IN (" + placeholders + ")", CATALOG_ROW,
                        distinct.toArray()).stream()
                .collect(Collectors.toMap(CatalogRow::stationId, Function.identity(), (first, second) -> first));
        Map<String, Station> valid = new java.util.LinkedHashMap<>(); // request order, so reads are deterministic
        for (String id : distinct) {
            CatalogRow row = rows.get(id);
            if (row != null) row.toStation().ifPresent(station -> valid.put(id, station));
        }
        RealtimeSnapshot snapshot = readRealtime(List.copyOf(valid.keySet()));
        Instant now = policy.now();
        List<StationLookup> result = new ArrayList<>(distinct.size());
        for (String id : distinct) {
            if (!rows.containsKey(id)) result.add(StationLookup.notFound(id));
            else if (!valid.containsKey(id) || snapshot.excludedIds().contains(id)) result.add(StationLookup.excluded(id));
            else result.add(StationLookup.of(candidate(join(valid.get(id), snapshot, null), now)));
        }
        return result;
    }

    @Override
    public RealtimeSummary realtimeSummary() {
        List<Station> catalog = searchCatalog();
        RealtimeSnapshot snapshot = readRealtime(catalog.stream().map(Station::stationId).toList());
        Instant lastSuccessAt;
        try { lastSuccessAt = realtime.lastSuccessAt().orElse(null); }
        catch (DataAccessException exception) { lastSuccessAt = null; }
        Instant now = policy.now();
        int fresh = 0, stale = 0, unavailable = 0;
        for (Station station : catalog) {
            if (snapshot.excludedIds().contains(station.stationId())) continue;
            var status = snapshot.statuses().get(station.stationId());
            switch (policy.classify(status == null ? null : status.updatedAt(), now).status()) {
                case FRESH -> fresh++;
                case STALE -> stale++;
                case UNAVAILABLE -> unavailable++;
            }
        }
        return new RealtimeSummary(fresh + stale + unavailable, fresh, stale, unavailable, lastSuccessAt, now);
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

    private StationCandidate candidate(Station station, Instant now) {
        var assessment = policy.classify(station.updatedAt(), now);
        if (assessment.status().servable()) return new StationCandidate(station, assessment.status(), assessment.ageSeconds());
        // Not servable at this instant: drop any realtime values so UNAVAILABLE never carries counts.
        return new StationCandidate(new Station(station.stationId(), station.stationName(), station.address(),
                station.location(), station.rackTotalCount(), null, station.distanceMeters(), null, null),
                assessment.status(), null);
    }

    /** Realtime store failures degrade to "no values" (all unknown) instead of failing the request. */
    private RealtimeSnapshot readRealtime(List<String> ids) {
        if (ids.isEmpty()) return RealtimeSnapshot.empty();
        try { return realtime.read(ids); }
        catch (DataAccessException exception) { return RealtimeSnapshot.empty(); }
    }

    /** Joins realtime values and drops stations excluded by the publication gate. */
    private List<Station> enrich(List<Station> candidates, Location center) {
        RealtimeSnapshot snapshot = readRealtime(candidates.stream().map(Station::stationId).toList());
        return candidates.stream().filter(station -> !snapshot.excludedIds().contains(station.stationId()))
                .map(station -> join(station, snapshot, center)).toList();
    }

    /** {@code center == null}: no query centre, distance 0. */
    private static Station join(Station station, RealtimeSnapshot snapshot, @Nullable Location center) {
        var status = snapshot.statuses().get(station.stationId());
        var location = status == null ? station.location() : new Location(status.latitude(), status.longitude());
        return new Station(station.stationId(), status == null ? station.stationName() : status.stationName(),
                station.address(), location, status == null ? station.rackTotalCount() : status.rackTotalCount(),
                status == null ? null : status.availableBikeCount(), center == null ? 0 : center.distanceTo(location),
                status == null ? null : status.updatedAt(), status == null ? null : status.sharedRate());
    }

    private static String address(@Nullable String address1, @Nullable String address2) {
        return String.join(" ", Stream.of(address1, address2).filter(value -> value != null && !value.isBlank()).toList());
    }
}
