package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshotSource;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationNameSearch;
import com.ddareungi.station.domain.StationSearchPort;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link StationSearchPort} served entirely from memory (R3): an immutable {@link StationCatalog} (grid index,
 * precomputed search keys) joined with the {@link RealtimeSnapshotSource} snapshot. No request touches MySQL or
 * Redis; both are refreshed in the background. Shared by the infra (MySQL + Redis) and local (sample) adapters.
 *
 * <p>Join rules (unchanged from the P2-1 adapter): a realtime value is joined only while servable (FRESH/STALE at
 * the call's single evaluation instant) and then supplies name, coordinates, rack count, available count, shared and
 * updatedAt; the address always comes from the catalog. Distances are measured from the joined location.
 * Gate-excluded ids (versioned values) are dropped everywhere except {@link #lookup} (EXCLUDED).
 */
abstract class CatalogStationSearch implements StationSearchPort {
    /**
     * Spatial candidates are taken by catalog coordinates with this margin, then filtered by the joined (realtime)
     * coordinates; a realtime coordinate further than this from its catalog coordinate is not found spatially.
     */
    static final double CANDIDATE_MARGIN_METERS = 500;
    private static final double CANDIDATE_MARGIN_DEGREES = 0.005;
    private static final Comparator<Station> BY_DISTANCE =
            Comparator.comparingDouble(Station::distanceMeters).thenComparing(Station::stationId);

    private final StationCatalogHolder catalogs;
    private final RealtimeSnapshotSource realtime;
    protected final RealtimeFreshnessPolicy policy;

    CatalogStationSearch(StationCatalogHolder catalogs, RealtimeSnapshotSource realtime, RealtimeFreshnessPolicy policy) {
        this.catalogs = catalogs;
        this.realtime = realtime;
        this.policy = policy;
    }

    StationCatalogHolder catalogs() { return catalogs; }

    @Override
    public List<Station> findNearby(Location center, int radiusMeters) {
        return within(center, radiusMeters, policy.now());
    }

    @Override
    public List<Station> findInBounds(StationBounds bounds) {
        return inBounds(bounds, policy.now());
    }

    /** Same set and order as {@link #findInBounds}, classified at the same single instant (R4 clusters). */
    @Override
    public List<StationCandidate> findCandidatesInBounds(StationBounds bounds) {
        Instant now = policy.now();
        return inBounds(bounds, now).stream().map(station -> candidate(station, now)).toList();
    }

    /** Public, non-excluded stations whose joined location is inside the box, nearest to its centre first. */
    private List<Station> inBounds(StationBounds bounds, Instant now) {
        StationCatalog catalog = catalogs.require();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        Location center = bounds.center();
        List<Station> result = new ArrayList<>();
        for (var entry : catalog.publicInBox(bounds.south() - CANDIDATE_MARGIN_DEGREES, bounds.north() + CANDIDATE_MARGIN_DEGREES,
                bounds.west() - CANDIDATE_MARGIN_DEGREES, bounds.east() + CANDIDATE_MARGIN_DEGREES)) {
            if (excluded(snapshot, entry)) continue;
            Station station = join(entry.station(), snapshot, center, now);
            if (bounds.contains(station.location())) result.add(station);
        }
        result.sort(BY_DISTANCE);
        return result;
    }

    @Override
    public List<Station> findByIds(List<String> ids, Location center) {
        if (ids.isEmpty()) return List.of();
        StationCatalog catalog = catalogs.require();
        Instant now = policy.now();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        List<Station> result = new ArrayList<>();
        for (String id : new LinkedHashSet<>(ids)) {
            var entry = id == null ? null : catalog.get(id);
            if (entry == null || !entry.publicEligible() || excluded(snapshot, entry)) continue;
            result.add(join(entry.station(), snapshot, center, now));
        }
        return result;
    }

    /** Ranks public, non-excluded stations by catalog name/address (precomputed keys), then joins at most 20. */
    @Override
    public List<Station> searchByName(String query, Location center) {
        StationCatalog catalog = catalogs.require();
        Instant now = policy.now();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        Set<String> excludedIds = snapshot.snapshot().excludedIds();
        List<StationNameSearch.Indexed> searchable = excludedIds.isEmpty() ? catalog.searchIndex()
                : catalog.searchIndex().stream().filter(indexed -> !excludedIds.contains(indexed.station().stationId())).toList();
        return StationNameSearch.selectIndexed(searchable, query, center).stream()
                .map(station -> join(station, snapshot, center, now)).toList();
    }

    @Override
    public List<StationCandidate> findWithin(Location center, int radiusMeters) {
        Instant now = policy.now();
        return within(center, radiusMeters, now).stream().map(station -> candidate(station, now)).toList();
    }

    @Override
    public List<StationLookup> lookup(List<String> ids) {
        List<String> distinct = List.copyOf(new LinkedHashSet<>(ids));
        if (distinct.isEmpty()) return List.of();
        StationCatalog catalog = catalogs.require();
        Instant now = policy.now();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        List<StationLookup> result = new ArrayList<>(distinct.size());
        for (String id : distinct) {
            var entry = catalog.get(id);
            if (entry == null) result.add(StationLookup.notFound(id));
            else if (!entry.publicEligible() || excluded(snapshot, entry)) result.add(StationLookup.excluded(id));
            else result.add(StationLookup.of(candidate(join(entry.station(), snapshot, null, now), now)));
        }
        return result;
    }

    @Override
    public RealtimeSummary realtimeSummary() {
        StationCatalog catalog = catalogs.require();
        Instant now = policy.now();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        int fresh = 0, stale = 0, unavailable = 0;
        for (var entry : catalog.publicEntries()) {
            if (excluded(snapshot, entry)) continue;
            RealtimeStationStatus status = snapshot.snapshot().statuses().get(entry.stationId());
            switch (policy.classify(status == null ? null : status.updatedAt(), now).status()) {
                case FRESH -> fresh++;
                case STALE -> stale++;
                case UNAVAILABLE -> unavailable++;
            }
        }
        return new RealtimeSummary(fresh + stale + unavailable, fresh, stale, unavailable, snapshot.lastSuccessAt(), now);
    }

    /** Public, non-excluded stations within the radius of their joined location, nearest first then stationId. */
    private List<Station> within(Location center, int radiusMeters, Instant now) {
        StationCatalog catalog = catalogs.require();
        CachedRealtimeSnapshot snapshot = realtime.current(now);
        List<Station> result = new ArrayList<>();
        for (var entry : catalog.publicWithin(center, radiusMeters + CANDIDATE_MARGIN_METERS)) {
            if (excluded(snapshot, entry)) continue;
            Station station = join(entry.station(), snapshot, center, now);
            if (station.distanceMeters() <= radiusMeters) result.add(station);
        }
        result.sort(BY_DISTANCE);
        return result;
    }

    private static boolean excluded(CachedRealtimeSnapshot snapshot, StationCatalog.Entry entry) {
        return snapshot.snapshot().excludedIds().contains(entry.stationId());
    }

    /** {@code center == null}: no query centre, distance 0. Values that are not servable at {@code now} are ignored. */
    private Station join(Station station, CachedRealtimeSnapshot snapshot, @Nullable Location center, Instant now) {
        RealtimeStationStatus status = snapshot.snapshot().statuses().get(station.stationId());
        if (status != null && !policy.classify(status.updatedAt(), now).status().servable()) status = null;
        Location location = status == null ? station.location() : new Location(status.latitude(), status.longitude());
        return new Station(station.stationId(), status == null ? station.stationName() : status.stationName(),
                station.address(), location, status == null ? station.rackTotalCount() : status.rackTotalCount(),
                status == null ? null : status.availableBikeCount(), center == null ? 0 : center.distanceTo(location),
                status == null ? null : status.updatedAt(), status == null ? null : status.sharedRate());
    }

    private StationCandidate candidate(Station station, Instant now) {
        var assessment = policy.classify(station.updatedAt(), now);
        if (assessment.status().servable()) return new StationCandidate(station, assessment.status(), assessment.ageSeconds());
        // Not servable at this instant: no realtime values so UNAVAILABLE never carries counts.
        return new StationCandidate(new Station(station.stationId(), station.stationName(), station.address(),
                station.location(), station.rackTotalCount(), null, station.distanceMeters(), null, null),
                assessment.status(), null);
    }
}
