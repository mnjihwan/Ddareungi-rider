package com.ddareungi.routing;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.ContextPropagation;
import com.ddareungi.global.resilience.Deadline;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Service
public class PlanBikeRoute {
    private final StationSearchPort stations;
    private final WalkingRouteProvider walking;
    private final BicycleRouteProvider bicycle;
    private final boolean enabled;
    private final Duration deadline;
    private final RealtimeFreshnessPolicy freshness;
    /** Test/compat constructor: D8 deadline and the default 120/600 s freshness policy. */
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         boolean enabled) {
        this(stations, walking, bicycle, enabled, ProviderHttpProperties.defaults().route().totalTimeout());
    }
    /** @param deadline budget of one whole plan, all provider calls and retries included (D8: 10s) */
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         boolean enabled, Duration deadline) {
        this(stations, walking, bicycle, enabled, deadline, RealtimeFreshnessPolicy.defaults());
    }
    /**
     * @param freshness the shared FRESH/STALE boundaries (spec v5 11.0); departures must be FRESH (D2), arrivals
     *                  FRESH or STALE (review M3). FRESH needs the collector's 60 s realtime job (spec 11.0/21.2):
     *                  with only the 5-minute collection most values are STALE and departures fail with 503.
     */
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         boolean enabled, Duration deadline, RealtimeFreshnessPolicy freshness) {
        if (freshness == null) throw new IllegalArgumentException("freshness policy is required");
        this.stations = stations; this.walking = walking; this.bicycle = bicycle; this.enabled = enabled;
        this.deadline = deadline; this.freshness = freshness;
    }
    @Autowired
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         @Value("${routing.enabled:true}") boolean enabled, ProviderHttpProperties http,
                         RealtimeFreshnessPolicy freshness) {
        this(stations, walking, bicycle, enabled, http.route().totalTimeout(), freshness);
    }
    public boolean configured() { return enabled && walking.configured() && bicycle.configured(); }

    public Plan execute(Location origin, Location destination, String departureStationId) {
        return execute(origin, destination, departureStationId, null);
    }

    public Plan execute(Location origin, Location destination, String departureStationId, String arrivalStationId) {
        if (origin == null || destination == null) throw new IllegalArgumentException("출발지와 목적지를 선택해 주세요.");
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요. 장소와 대여소 조회는 계속 이용할 수 있어요.");
        // The whole plan runs on a virtual thread under one deadline; past it, the work is cancelled (interrupted)
        // and the client gets 504 EXTERNAL_API_TIMEOUT. Provider retries inside see the same deadline.
        var budget = Deadline.after(deadline);
        var plan = ContextPropagation.start("route-plan",
                () -> budget.supply(() -> plan(origin, destination, departureStationId, arrivalStationId)));
        try {
            return plan.get(budget.remaining().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException error) {
            plan.cancel(true);
            throw KakaoRouteClient.timeout();
        } catch (InterruptedException error) {
            plan.cancel(true);
            Thread.currentThread().interrupt();
            throw KakaoRouteClient.timeout();
        } catch (ExecutionException error) {
            throw ContextPropagation.unwrap(error);
        }
    }

    private Plan plan(Location origin, Location destination, String departureStationId, String arrivalStationId) {
        // One evaluation instant per request: every candidate (and the post-routing recheck) is judged against it.
        var now = freshness.now();
        var departures = candidates(origin, true, departureStationId, now);
        var arrivals = candidates(destination, false, arrivalStationId, now);
        if (departures.isEmpty()) throw new RouteException(HttpStatus.NOT_FOUND, "NO_RENTAL_STATION", "출발지 1km 안에 대여 가능한 자전거가 확인된 대여소가 없어요. 출발지나 지정 대여소를 변경해 주세요.");
        if (arrivals.isEmpty()) throw new RouteException(HttpStatus.NOT_FOUND, "NO_RETURN_STATION", "목적지 1km 안에 최근 정보가 확인된 대여소가 없어요. 목적지를 변경해 주세요.");
        var starts = departures.stream().map(Station::location).toList();
        var ends = arrivals.stream().map(Station::location).toList();
        var walk = walking.forRequest();
        var ride = bicycle.forRequest();
        var first = walk.durations(List.of(origin), starts);
        var last = walk.durations(ends, List.of(destination));
        var cycle = ride.durations(starts, ends);
        int startIndex = -1, endIndex = -1; double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < starts.size(); i++) for (int j = 0; j < ends.size(); j++) {
            if (departures.get(i).stationId().equals(arrivals.get(j).stationId())) continue;
            if (first[0][i] == null || last[j][0] == null || cycle[i][j] == null) continue;
            double seconds = first[0][i] + cycle[i][j] + last[j][0];
            if (seconds < best) { best = seconds; startIndex = i; endIndex = j; }
        }
        if (startIndex < 0) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONNECTED_ROUTE", "주변 대여소를 연결하는 도보·자전거 경로를 찾지 못했어요. 출발지나 목적지를 변경해 주세요.");
        var departure = departures.get(startIndex); var arrival = arrivals.get(endIndex);
        var legs = List.of(walk.route(origin, departure.location()),
                ride.route(departure.location(), arrival.location()),
                walk.route(arrival.location(), destination));
        // Check again after external routing calls, when stock may have changed.
        departure = stations.findByIds(List.of(departure.stationId()), origin).stream()
                .filter(s -> fresh(s, now) && s.availableBikeCount() > 0).findFirst()
                .orElseThrow(() -> new RouteException(HttpStatus.CONFLICT, "RENTAL_STOCK_CHANGED", "대여소 수량이 바뀌었어요. 경로를 다시 검색해 주세요."));
        return new Plan(origin, destination, departure, arrival, legs,
                legs.stream().mapToDouble(RouteProvider.Leg::distanceMeters).sum(),
                legs.stream().mapToDouble(RouteProvider.Leg::durationSeconds).sum(), Instant.now(),
                attribution(walk.attribution(), ride.attribution()));
    }
    // Same engine for both segments yields one credit; different providers are both credited.
    private static String attribution(String walking, String cycling) {
        return walking.equals(cycling) ? walking : walking + " / " + cycling;
    }
    private List<Station> candidates(Location location, boolean rental, String pinnedId, Instant now) {
        if (pinnedId != null && !pinnedId.isBlank()) {
            var pinned = stations.findByIds(List.of(pinnedId), location).stream()
                    .filter(s -> s.stationId().equals(pinnedId)).findFirst()
                    .orElseThrow(() -> new RouteException(HttpStatus.NOT_FOUND, "PINNED_STATION_NOT_FOUND", "지정한 대여소를 찾지 못했어요. 자동 선택하거나 다른 대여소를 지정해 주세요."));
            // Review M3(a): a pinned departure needs a FRESH count; a pinned arrival only has to be servable
            // (FRESH or STALE), since return availability is UNKNOWN in v1 either way (spec 11.0 / route contract).
            if (!(rental ? fresh(pinned, now) : returnable(pinned, now))) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "지정한 대여소의 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색하거나 다른 대여소를 선택해 주세요.");
            if (rental && pinned.availableBikeCount() == 0) throw new RouteException(HttpStatus.CONFLICT, "PINNED_STATION_EMPTY", "지정한 대여소에 대여 가능한 자전거가 없어요. 자동 선택하거나 다른 대여소를 지정해 주세요.");
            return List.of(pinned);
        }
        var nearby = stations.findNearby(location, 1000);
        if (rental) {
            var usable = nearby.stream().filter(s -> fresh(s, now) && s.availableBikeCount() > 0)
                    .sorted(Comparator.comparingDouble(s -> location.distanceTo(s.location()))).toList();
            if (usable.isEmpty() && !nearby.isEmpty()) {
                // Review M3(b), spec 11.0 decision table: "no bikes" (NO_RENTAL_BIKES -> 422) only when every
                // candidate is FRESH with 0 bikes. If any candidate is STALE/UNAVAILABLE its stock is unknown, so
                // the answer is 503 (STATION_STATUS_UNAVAILABLE -> public REALTIME_DATA_UNAVAILABLE), not "no bikes".
                if (nearby.stream().allMatch(s -> fresh(s, now)))
                    throw new RouteException(HttpStatus.NOT_FOUND, "NO_RENTAL_BIKES", "출발지 1km 안에 대여소는 있지만 대여 가능한 자전거가 확인되지 않았어요. 출발 장소를 변경하거나 다른 대여소를 지정해 주세요.");
                throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "출발지 주변 대여소는 있지만 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색해 주세요.");
            }
            return nearest(location, usable);
        }
        // Review M3(a): return candidates exclude only UNAVAILABLE stations; STALE ones stay (spec 11.0).
        var usable = nearby.stream().filter(s -> returnable(s, now))
                .sorted(Comparator.comparingDouble(s -> location.distanceTo(s.location()))).toList();
        if (usable.isEmpty() && !nearby.isEmpty())
            throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "목적지 주변 대여소는 있지만 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색해 주세요.");
        return nearest(location, usable);
    }
    private static List<Station> nearest(Location location, List<Station> usable) {
        var within500 = usable.stream().filter(s -> location.distanceTo(s.location()) <= 500).toList();
        return (within500.isEmpty() ? usable : within500).stream().limit(3).toList();
    }
    // D2: a departure candidate needs a known stock and a FRESH value under the shared policy (age <= fresh-seconds,
    // at most 30 s in the future). STALE and UNAVAILABLE values are never departure candidates.
    private boolean fresh(Station station, Instant now) {
        return station.location() != null && station.availableBikeCount() != null && station.availableBikeCount() >= 0
                && freshness.classify(station.updatedAt(), now).status() == RealtimeFreshness.FRESH;
    }
    // Review M3(a): an arrival candidate only has to be servable (FRESH or STALE, i.e. not UNAVAILABLE). Its bike
    // count is not used: return availability is UNKNOWN in v1.
    private boolean returnable(Station station, Instant now) {
        return station.location() != null
                && freshness.classify(station.updatedAt(), now).status() != RealtimeFreshness.UNAVAILABLE;
    }
    public record Plan(Location origin, Location destination, Station departureStation, Station arrivalStation,
                       List<RouteProvider.Leg> legs, double distanceMeters, double durationSeconds,
                       Instant generatedAt, String attribution) {}
}
