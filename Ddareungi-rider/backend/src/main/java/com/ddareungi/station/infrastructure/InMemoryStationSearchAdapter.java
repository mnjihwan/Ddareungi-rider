package com.ddareungi.station.infrastructure;

import com.ddareungi.realtime.application.CachedRealtimeSnapshot;
import com.ddareungi.realtime.application.RealtimeSnapshot;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.realtime.domain.RealtimeStationStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Sample data for the local profile, served through the same in-memory catalog and snapshot abstractions as the
 * infra adapter (R3). Every seed is public and "collected" at the evaluation instant (FRESH, age 0); there is no
 * publication gate or realtime store, so EXCLUDED/UNAVAILABLE never occur here.
 */
@Profile("local")
@Repository
public class InMemoryStationSearchAdapter extends CatalogStationSearch {
    private static final List<Seed> SEEDS = List.of(
            new Seed("ST-001", "강남역 2번 출구", "서울특별시 강남구 강남대로", 37.4979, 127.0276, 20, 7),
            new Seed("ST-002", "역삼역 4번 출구", "서울특별시 강남구 테헤란로", 37.5006, 127.0364, 15, 2),
            new Seed("ST-003", "신논현역 6번 출구", "서울특별시 강남구 봉은사로", 37.5045, 127.0248, 25, 11)
    );

    public InMemoryStationSearchAdapter() {
        this(RealtimeFreshnessPolicy.defaults());
    }

    @Autowired
    public InMemoryStationSearchAdapter(RealtimeFreshnessPolicy policy) {
        super(loadedCatalog(), now -> {
            // Sample values are produced at the evaluation instant, which therefore doubles as the last success time.
            Map<String, RealtimeStationStatus> statuses = SEEDS.stream()
                    .map(seed -> seed.status(now)).collect(Collectors.toMap(RealtimeStationStatus::stationId, Function.identity()));
            return new CachedRealtimeSnapshot(new RealtimeSnapshot(statuses, Set.of()), now, now);
        }, policy);
    }

    private static StationCatalogHolder loadedCatalog() {
        var holder = new StationCatalogHolder(() -> SEEDS.stream().map(Seed::row).toList(), Clock.systemUTC());
        holder.reload();
        return holder;
    }

    private record Seed(String id, String name, String address, double latitude, double longitude, int racks, int bikes) {
        StationCatalog.Row row() {
            return new StationCatalog.Row(id, name, address, latitude, longitude, racks);
        }

        RealtimeStationStatus status(java.time.Instant now) {
            // Sample occupancy ratio (%) in the source's integer style, e.g. 7/20 → 35.0.
            double shared = Math.round(bikes * 100.0 / racks);
            return new RealtimeStationStatus(id, name, racks, bikes, shared, latitude, longitude, now);
        }
    }
}
