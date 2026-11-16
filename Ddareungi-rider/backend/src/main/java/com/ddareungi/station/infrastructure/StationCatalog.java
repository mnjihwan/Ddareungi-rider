package com.ddareungi.station.infrastructure;

import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationNameSearch;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable snapshot of the station catalog (MySQL {@code station}, R3): every row, a public-eligibility flag per row,
 * search keys precomputed for public rows and a grid index over public rows. Replaced as a whole (volatile reference
 * in {@link StationCatalogHolder}); never mutated after construction, so request threads read it without locks.
 */
final class StationCatalog {
    /** One catalog row as stored; name and coordinates may be missing or invalid. */
    record Row(String stationId, @Nullable String stationName, String address,
               @Nullable Double latitude, @Nullable Double longitude, @Nullable Integer rackTotalCount) {}

    /**
     * A catalog row. {@code publicEligible} requires a non-blank name and valid coordinates; only eligible entries
     * carry {@code station} (distance 0, no realtime values) and {@code search} keys.
     */
    record Entry(String stationId, boolean publicEligible, @Nullable Station station,
                 StationNameSearch.@Nullable Indexed search) {}

    private final Map<String, Entry> byId;
    private final List<Entry> publicEntries;
    private final List<String> publicIds;
    private final List<StationNameSearch.Indexed> searchIndex;
    private final StationGridIndex<Entry> grid;
    private final Instant loadedAt;

    private StationCatalog(Map<String, Entry> byId, List<Entry> publicEntries, Instant loadedAt) {
        this.byId = Collections.unmodifiableMap(byId);
        this.publicEntries = List.copyOf(publicEntries);
        this.publicIds = publicEntries.stream().map(Entry::stationId).toList();
        this.searchIndex = publicEntries.stream().map(Entry::search).toList();
        this.grid = new StationGridIndex<>(this.publicEntries, entry -> entry.station().location());
        this.loadedAt = loadedAt;
    }

    static StationCatalog build(List<Row> rows, Instant loadedAt) {
        Map<String, Entry> byId = new LinkedHashMap<>();
        List<Entry> publicEntries = new ArrayList<>();
        for (Row row : rows) {
            if (row.stationId() == null || byId.containsKey(row.stationId())) continue; // first row wins
            Station station = toStation(row);
            Entry entry = station == null ? new Entry(row.stationId(), false, null, null)
                    : new Entry(row.stationId(), true, station, StationNameSearch.Indexed.of(station));
            byId.put(row.stationId(), entry);
            if (entry.publicEligible()) publicEntries.add(entry);
        }
        return new StationCatalog(byId, publicEntries, loadedAt);
    }

    /** Public catalog requirements: non-blank name and valid coordinates. */
    private static @Nullable Station toStation(Row row) {
        if (row.stationName() == null || row.stationName().isBlank() || row.latitude() == null || row.longitude() == null) return null;
        try {
            return new Station(row.stationId(), row.stationName(), row.address(), new Location(row.latitude(), row.longitude()),
                    row.rackTotalCount(), null, 0, null);
        } catch (Location.InvalidCoordinateException exception) {
            return null;
        }
    }

    @Nullable Entry get(String stationId) { return byId.get(stationId); }

    List<Entry> publicEntries() { return publicEntries; }

    /** Ids of public entries; the same instance for the lifetime of this catalog (the legacy poller relies on it). */
    List<String> publicIds() { return publicIds; }

    List<StationNameSearch.Indexed> searchIndex() { return searchIndex; }

    List<Entry> publicWithin(Location center, double radiusMeters) { return grid.withinRadius(center, radiusMeters); }

    List<Entry> publicInBox(double south, double north, double west, double east) { return grid.inBox(south, north, west, east); }

    int size() { return byId.size(); }

    Instant loadedAt() { return loadedAt; }
}
