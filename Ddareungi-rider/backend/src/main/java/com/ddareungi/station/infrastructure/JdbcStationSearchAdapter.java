package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.RealtimeSnapshotSource;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.stream.Stream;

/**
 * MySQL station catalog joined with the Redis realtime snapshot, both held in memory (R3). The catalog is loaded
 * with one full-table query by {@link #reloadCatalog()} (at startup and every {@code station.catalog.reload-interval},
 * driven by {@link StationReadModelScheduler}); the realtime snapshot is polled by
 * {@link com.ddareungi.realtime.infrastructure.RealtimeSnapshotCache}. Port methods read memory only: no bbox SQL and
 * no per-request Redis reads. Without a loaded catalog every port method throws {@code ApiException(SERVICE_UNAVAILABLE)}
 * (except {@code lookup} of no ids).
 */
@Profile("infra")
@Repository
public class JdbcStationSearchAdapter extends CatalogStationSearch {
    static final String CATALOG_SQL =
            "SELECT station_id, station_name, address1, address2, latitude, longitude, rack_total_count FROM station";
    /** Nullable view of every row: NULL rack count stays null (unknown), never 0; invalid rows become non-public. */
    static final RowMapper<StationCatalog.Row> ROW = (rs, row) -> new StationCatalog.Row(rs.getString("station_id"),
            rs.getString("station_name"), address(rs.getString("address1"), rs.getString("address2")),
            rs.getObject("latitude", Double.class), rs.getObject("longitude", Double.class),
            rs.getObject("rack_total_count", Integer.class));

    @Autowired
    public JdbcStationSearchAdapter(JdbcTemplate jdbc, RealtimeSnapshotSource realtime, RealtimeFreshnessPolicy policy) {
        super(new StationCatalogHolder(() -> jdbc.query(CATALOG_SQL, ROW), Clock.systemUTC()), realtime, policy);
    }

    /** Loads the whole catalog; on failure the previous catalog stays. */
    public boolean reloadCatalog() { return catalogs().reload(); }

    public boolean catalogLoaded() { return catalogs().loaded(); }

    /** Public station ids of the current catalog (same instance until the next reload); empty before the first load. */
    public List<String> catalogStationIds() {
        StationCatalog catalog = catalogs().current();
        return catalog == null ? List.of() : catalog.publicIds();
    }

    @Nullable StationCatalog catalog() { return catalogs().current(); }

    private static String address(@Nullable String address1, @Nullable String address2) {
        return String.join(" ", Stream.of(address1, address2).filter(value -> value != null && !value.isBlank()).toList());
    }
}
