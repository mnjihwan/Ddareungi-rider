package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.RealtimeSummary;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationBounds;
import com.ddareungi.station.domain.StationCandidate;
import com.ddareungi.station.domain.StationLookup;
import com.ddareungi.station.domain.StationNameSearch;
import com.ddareungi.station.domain.StationSearchPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sample data for the local profile. Every seed is public and "collected" at the evaluation instant (FRESH, age 0);
 * there is no publication gate or realtime store, so EXCLUDED/UNAVAILABLE never occur here.
 */
@Profile("local")
@Repository
public class InMemoryStationSearchAdapter implements StationSearchPort {
    private final List<Seed> seeds = List.of(
            new Seed("ST-001", "강남역 2번 출구", "서울특별시 강남구 강남대로", 37.4979, 127.0276, 20, 7),
            new Seed("ST-002", "역삼역 4번 출구", "서울특별시 강남구 테헤란로", 37.5006, 127.0364, 15, 2),
            new Seed("ST-003", "신논현역 6번 출구", "서울특별시 강남구 봉은사로", 37.5045, 127.0248, 25, 11)
    );
    private final RealtimeFreshnessPolicy policy;

    public InMemoryStationSearchAdapter() {
        this(RealtimeFreshnessPolicy.defaults());
    }

    @Autowired
    public InMemoryStationSearchAdapter(RealtimeFreshnessPolicy policy) {
        this.policy = policy;
    }

    @Override
    public List<Station> findNearby(Location center, int radiusMeters) {
        Instant now = policy.now();
        return seeds.stream()
                .map(seed -> seed.toStation(center, now))
                .filter(station -> station.distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingDouble(Station::distanceMeters))
                .toList();
    }

    @Override
    public List<Station> findInBounds(StationBounds bounds) {
        Instant now = policy.now();
        return seeds.stream().map(seed -> seed.toStation(bounds.center(), now))
                .filter(station -> bounds.contains(station.location())).toList();
    }

    @Override
    public List<Station> findByIds(List<String> ids, Location center) {
        Instant now = policy.now();
        return seeds.stream().filter(seed -> ids.contains(seed.id())).map(seed -> seed.toStation(center, now)).toList();
    }

    @Override
    public List<Station> searchByName(String query, Location center) {
        Instant now = policy.now();
        return StationNameSearch.select(seeds.stream().map(seed -> seed.toStation(center, now)).toList(), query, center);
    }

    @Override
    public List<StationCandidate> findWithin(Location center, int radiusMeters) {
        Instant now = policy.now();
        return seeds.stream().map(seed -> candidate(seed.toStation(center, now), now))
                .filter(candidate -> candidate.station().distanceMeters() <= radiusMeters)
                .sorted(Comparator.comparingDouble((StationCandidate c) -> c.station().distanceMeters())
                        .thenComparing(StationCandidate::stationId))
                .toList();
    }

    @Override
    public List<StationLookup> lookup(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        Instant now = policy.now();
        Map<String, Seed> byId = seeds.stream().collect(Collectors.toMap(Seed::id, Function.identity()));
        return new LinkedHashSet<>(ids).stream().map(id -> {
            Seed seed = byId.get(id);
            return seed == null ? StationLookup.notFound(id) : StationLookup.of(candidate(seed.toStation(null, now), now));
        }).toList();
    }

    @Override
    public RealtimeSummary realtimeSummary() {
        Instant now = policy.now();
        // Sample values are produced at the evaluation instant, which therefore doubles as the last success time.
        return new RealtimeSummary(seeds.size(), seeds.size(), 0, 0, now, now);
    }

    private StationCandidate candidate(Station station, Instant now) {
        var assessment = policy.classify(station.updatedAt(), now);
        return new StationCandidate(station, assessment.status(), assessment.ageSeconds());
    }

    private record Seed(String id, String name, String address, double latitude, double longitude, int racks, int bikes) {
        /** {@code center == null}: no query centre, distance 0. */
        Station toStation(Location center, Instant now) {
            Location location = new Location(latitude, longitude);
            // Sample occupancy ratio (%) in the source's integer style, e.g. 7/20 → 35.0.
            double shared = Math.round(bikes * 100.0 / racks);
            return new Station(id, name, address, location, racks, bikes,
                    center == null ? 0 : distance(center, location), now, shared);
        }

        private static double distance(Location a, Location b) {
            double earthRadius = 6_371_000;
            double lat = Math.toRadians(b.latitude() - a.latitude());
            double lon = Math.toRadians(b.longitude() - a.longitude());
            double value = Math.sin(lat / 2) * Math.sin(lat / 2)
                    + Math.cos(Math.toRadians(a.latitude())) * Math.cos(Math.toRadians(b.latitude()))
                    * Math.sin(lon / 2) * Math.sin(lon / 2);
            return earthRadius * 2 * Math.atan2(Math.sqrt(value), Math.sqrt(1 - value));
        }
    }
}
