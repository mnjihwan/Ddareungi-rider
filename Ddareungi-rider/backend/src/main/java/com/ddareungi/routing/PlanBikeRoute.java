package com.ddareungi.routing;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.ContextPropagation;
import com.ddareungi.global.resilience.Deadline;
import com.ddareungi.realtime.domain.RealtimeFreshness;
import com.ddareungi.realtime.domain.RealtimeFreshnessPolicy;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.Station;
import com.ddareungi.station.domain.StationSearchPort;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Bike route search (spec 13.1, A6). Up to {@code routing.search.max-station-candidates} departures × arrivals are
 * compared with real walking/cycling routes (Kakao: at most 6 + 9 = 15 directions calls, every later leg comes from
 * the per-request cache; cycling pairs are only asked when both of their walks are routed, review H1), ranked by total duration → walking distance → total distance → departure id → arrival id, and the
 * best {@code maxCandidates} are returned. {@link #execute} is the legacy {@code /routes/plan} entry point: the same
 * search with {@link RouteSearchOptions#legacyPlan()}, answering the recommended route in the former shape.
 */
@Service
public class PlanBikeRoute {
    private static final double WITHIN_PREFERRED_METERS = 500;
    private final StationSearchPort stations;
    private final WalkingRouteProvider walking;
    private final BicycleRouteProvider bicycle;
    private final boolean enabled;
    private final Duration deadline;
    private final RealtimeFreshnessPolicy freshness;
    private final int maxStationCandidates;
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
        this(stations, walking, bicycle, enabled, deadline, freshness, RouteSearchProperties.defaults());
    }
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         boolean enabled, Duration deadline, RealtimeFreshnessPolicy freshness,
                         RouteSearchProperties search) {
        if (freshness == null) throw new IllegalArgumentException("freshness policy is required");
        this.stations = stations; this.walking = walking; this.bicycle = bicycle; this.enabled = enabled;
        this.deadline = deadline; this.freshness = freshness; this.maxStationCandidates = search.maxStationCandidates();
    }
    @Autowired
    public PlanBikeRoute(StationSearchPort stations, WalkingRouteProvider walking, BicycleRouteProvider bicycle,
                         @Value("${routing.enabled:true}") boolean enabled, ProviderHttpProperties http,
                         RealtimeFreshnessPolicy freshness, RouteSearchProperties search) {
        this(stations, walking, bicycle, enabled, http.route().totalTimeout(), freshness, search);
    }
    public boolean configured() { return enabled && walking.configured() && bicycle.configured(); }
    /** Review L3: whether {@code preferBikeRoad} can be honoured both ways by the current engine ({@code /routes/status}). */
    public boolean supportsBikeRoadPreference() { return bicycle.supportsBikeRoadPreference(); }

    public Plan execute(Location origin, Location destination, String departureStationId) {
        return execute(origin, destination, departureStationId, null);
    }

    /** Legacy {@code /routes/plan}: the recommended route of a {@link RouteSearchOptions#legacyPlan()} search. */
    public Plan execute(Location origin, Location destination, String departureStationId, String arrivalStationId) {
        var result = search(new RouteSearchCriteria(origin, destination, RouteSearchOptions.legacyPlan(),
                departureStationId, arrivalStationId));
        var route = result.recommended();
        return new Plan(origin, destination, route.departure(), route.arrival(), route.legs(),
                route.legs().stream().mapToDouble(RouteProvider.Leg::distanceMeters).sum(),
                route.legs().stream().mapToDouble(RouteProvider.Leg::durationSeconds).sum(), result.generatedAt(),
                result.attribution());
    }

    public RouteSearchResult search(RouteSearchCriteria criteria) {
        if (criteria.origin() == null || criteria.destination() == null) throw new IllegalArgumentException("출발지와 목적지를 선택해 주세요.");
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요. 장소와 대여소 조회는 계속 이용할 수 있어요.");
        // Spec 13.1: an unsupported preferBikeRoad=true is refused, never silently ignored. Checked before any call.
        if (Boolean.TRUE.equals(criteria.options().preferBikeRoad()) && !bicycle.supportsBikeRoadPreference())
            throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_ROUTE_OPTION", "현재 경로 제공자는 자전거도로 우선(preferBikeRoad=true)을 지원하지 않아요. preferBikeRoad를 false로 지정해 주세요.");
        // The whole search runs on a virtual thread under one deadline; past it, the work is cancelled (interrupted)
        // and the client gets 504 EXTERNAL_API_TIMEOUT. Provider retries inside see the same deadline.
        var budget = Deadline.after(deadline);
        var plan = ContextPropagation.start("route-plan", () -> budget.supply(() -> run(criteria)));
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

    /** A routable candidate pair with its matrix values, rounded per segment (phase 3 decision 8). */
    private record Ranked(int start, int end, long durationSeconds, long walkingMeters, long totalMeters,
                          String departureId, String arrivalId) {}
    private static final Comparator<Ranked> ORDER = Comparator.comparingLong(Ranked::durationSeconds)
            .thenComparingLong(Ranked::walkingMeters).thenComparingLong(Ranked::totalMeters)
            .thenComparing(Ranked::departureId).thenComparing(Ranked::arrivalId);

    private RouteSearchResult run(RouteSearchCriteria criteria) {
        var options = criteria.options();
        var origin = criteria.origin(); var destination = criteria.destination();
        // One evaluation instant per request: every candidate (and the post-routing recheck) is judged against it.
        var now = freshness.now();
        var departures = candidates(origin, true, criteria.pinnedDeparture(), now, options);
        var arrivals = candidates(destination, false, criteria.pinnedArrival(), now, options);
        String radius = distanceLabel(options.stationSearchRadiusMeters());
        if (departures.isEmpty()) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_RENTAL_STATION", "출발지 " + radius + " 안에 대여 가능한 자전거가 확인된 대여소가 없어요. 출발지나 지정 대여소를 변경해 주세요.");
        if (arrivals.isEmpty()) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_RETURN_STATION", "목적지 " + radius + " 안에 최근 정보가 확인된 대여소가 없어요. 목적지를 변경해 주세요.");
        var starts = departures.stream().map(Station::location).toList();
        var ends = arrivals.stream().map(Station::location).toList();
        var walk = walking.forRequest();
        // null keeps the engine default (legacy adapter) and uses the same request-scoped entry point as before.
        var ride = options.preferBikeRoad() == null ? bicycle.forRequest() : bicycle.forRequest(options.preferBikeRoad());
        boolean bikeRoadPreferred = !Boolean.FALSE.equals(options.preferBikeRoad()) && bicycle.supportsBikeRoadPreference();
        var first = walk.matrix(List.of(origin), starts);
        var last = walk.matrix(ends, List.of(destination));
        // Review H1: a pair can only be ranked when both of its walks are routed, so the cycling matrix is asked only
        // for departures whose first walk and arrivals whose last walk are routed (the walks depend on one side each,
        // so those pairs are exactly this sub-matrix). Saves cycling calls (quota) after walking failures or "no road"
        // answers; ranking is unchanged because the skipped pairs could never be ranked. rideRow/rideColumn map a
        // candidate index to its index in the cycling matrix (-1: not asked).
        var rideRow = new int[starts.size()];
        var rideColumn = new int[ends.size()];
        var rideStarts = new ArrayList<Location>();
        var rideEnds = new ArrayList<Location>();
        for (int i = 0; i < starts.size(); i++) {
            rideRow[i] = first.cell(0, i).routed() ? rideStarts.size() : -1;
            if (rideRow[i] >= 0) rideStarts.add(starts.get(i));
        }
        for (int j = 0; j < ends.size(); j++) {
            rideColumn[j] = last.cell(j, 0).routed() ? rideEnds.size() : -1;
            if (rideColumn[j] >= 0) rideEnds.add(ends.get(j));
        }
        var cycle = rideStarts.isEmpty() || rideEnds.isEmpty() ? null : ride.matrix(rideStarts, rideEnds);

        // Evaluate every pair (same station excluded). A pair with a "no road" segment is unreachable; one with a
        // failed segment is unknown and makes the answer partial, never "no route" (spec 13.1).
        // Review H1: the walks are judged first (cycling was not asked when one of them is not routed). Compared with
        // judging all three segments at once this only differs when a walk failed and the (now unasked) cycling
        // segment would have been "no road" or failed as well: such a pair now counts as the walk failure.
        var ranked = new ArrayList<Ranked>();
        var failures = new ArrayList<RouteException>();
        int overWalkingLimit = 0;
        for (int i = 0; i < starts.size(); i++) for (int j = 0; j < ends.size(); j++) {
            if (departures.get(i).stationId().equals(arrivals.get(j).stationId())) continue;
            var walkIn = first.cell(0, i);
            var walkOut = last.cell(j, 0);
            if (walkIn.noRoad() || walkOut.noRoad()) continue;
            if (walkIn.failed()) { failures.add(walkIn.failure()); continue; }
            if (walkOut.failed()) { failures.add(walkOut.failure()); continue; }
            var bike = cycle.cell(rideRow[i], rideColumn[j]);
            if (bike.noRoad()) continue;
            if (bike.failed()) { failures.add(bike.failure()); continue; }
            var segments = List.of(walkIn, bike, walkOut);
            long walkingMeters = round(segments.get(0).distanceMeters()) + round(segments.get(2).distanceMeters());
            if (options.maxWalkingDistanceMeters() != null && walkingMeters > options.maxWalkingDistanceMeters()) {
                overWalkingLimit++;
                continue;
            }
            ranked.add(new Ranked(i, j,
                    segments.stream().mapToLong(cell -> round(cell.durationSeconds())).sum(), walkingMeters,
                    segments.stream().mapToLong(cell -> round(cell.distanceMeters())).sum(),
                    departures.get(i).stationId(), arrivals.get(j).stationId()));
        }
        if (ranked.isEmpty()) {
            // Spec 13.1 / phase 3 decision 7: no complete route -> the first provider error (pair order, then
            // segment order walk → bike → walk); decision 4: everything over the walking limit -> 404.
            if (!failures.isEmpty()) throw failures.getFirst();
            if (overWalkingLimit > 0) throw new RouteException(HttpStatus.NOT_FOUND, "NO_CONNECTED_ROUTE", "도보 거리 " + distanceLabel(options.maxWalkingDistanceMeters()) + " 안에서 연결되는 경로를 찾지 못했어요. 도보 거리 상한을 늘리거나 출발지·목적지를 변경해 주세요.");
            throw new RouteException(HttpStatus.NOT_FOUND, "NO_CONNECTED_ROUTE", "주변 대여소를 연결하는 도보·자전거 경로를 찾지 못했어요. 출발지나 목적지를 변경해 주세요.");
        }
        ranked.sort(ORDER);
        boolean partial = !failures.isEmpty();

        // Legs of the best maxCandidates pairs only. Kakao: every leg is in the per-request cache, 0 extra calls.
        // ORS has no per-request cache (forRequest() is the engine itself), so each returned route costs its own
        // 3 directions calls: at most maxCandidates (5) × 3 = 15 calls after the matrices (review L4).
        var routes = new ArrayList<RouteSearchResult.Route>();
        var legFailures = new ArrayList<RouteException>();
        for (var pair : ranked.subList(0, Math.min(options.maxCandidates(), ranked.size()))) {
            var departure = departures.get(pair.start()); var arrival = arrivals.get(pair.end());
            try {
                routes.add(new RouteSearchResult.Route(departure, arrival, List.of(walk.route(origin, departure.location()),
                        ride.route(departure.location(), arrival.location()),
                        walk.route(arrival.location(), destination))));
            } catch (RouteException error) {
                if (error.code.equals("NO_ROAD_ROUTE")) continue;
                legFailures.add(error);
            }
        }
        partial |= !legFailures.isEmpty();

        // Check again after the external routing calls, when stock may have changed (one lookup for all departures).
        // A route whose departure lost its bikes is dropped and the next one moves up (phase 3 decision 6, no extra
        // provider call); 409 only when none is left. With excludeStationsWithoutBikes=false nothing is dropped.
        var ids = new ArrayList<>(new LinkedHashSet<>(routes.stream().map(route -> route.departure().stationId()).toList()));
        var latest = new HashMap<String, Station>();
        if (!ids.isEmpty()) for (var station : stations.findByIds(ids, origin)) latest.putIfAbsent(station.stationId(), station);
        var confirmed = new ArrayList<RouteSearchResult.Route>();
        boolean stockChanged = false;
        for (var route : routes) {
            var current = latest.get(route.departure().stationId());
            if (options.excludeStationsWithoutBikes()) {
                if (current == null || !fresh(current, now) || current.availableBikeCount() <= 0) { stockChanged = true; continue; }
            } else if (current == null) {
                current = route.departure();
            }
            confirmed.add(new RouteSearchResult.Route(current, route.arrival(), route.legs()));
        }
        if (confirmed.isEmpty()) {
            if (stockChanged) throw new RouteException(HttpStatus.CONFLICT, "RENTAL_STOCK_CHANGED", "대여소 수량이 바뀌었어요. 경로를 다시 검색해 주세요.");
            if (!legFailures.isEmpty()) throw legFailures.getFirst();
            throw new RouteException(HttpStatus.NOT_FOUND, "NO_CONNECTED_ROUTE", "주변 대여소를 연결하는 도보·자전거 경로를 찾지 못했어요. 출발지나 목적지를 변경해 주세요.");
        }
        // generatedAt is the request's single evaluation instant (injected policy clock, read once per request as
        // D2 requires), not Instant.now().
        return new RouteSearchResult(origin, destination, confirmed, partial, bikeRoadPreferred, now,
                attribution(walk.attribution(), ride.attribution()));
    }
    // Same engine for both segments yields one credit; different providers are both credited.
    private static String attribution(String walking, String cycling) {
        return walking.equals(cycling) ? walking : walking + " / " + cycling;
    }
    private static long round(@Nullable Double value) { return value == null ? 0 : Math.round(value); }
    private static String distanceLabel(int meters) {
        return meters >= 1000 && meters % 1000 == 0 ? (meters / 1000) + "km" : meters + "m";
    }
    private List<Station> candidates(Location location, boolean rental, @Nullable String pinnedId, Instant now,
                                     RouteSearchOptions options) {
        boolean requireBikes = options.excludeStationsWithoutBikes();
        if (pinnedId != null && !pinnedId.isBlank()) {
            var pinned = stations.findByIds(List.of(pinnedId), location).stream()
                    .filter(s -> s.stationId().equals(pinnedId)).findFirst()
                    .orElseThrow(() -> new RouteException(HttpStatus.NOT_FOUND, "PINNED_STATION_NOT_FOUND", "지정한 대여소를 찾지 못했어요. 자동 선택하거나 다른 대여소를 지정해 주세요."));
            // Review M3(a): a pinned departure needs a FRESH count; a pinned arrival only has to be servable
            // (FRESH or STALE), since return availability is UNKNOWN in v1 either way (spec 11.0 / route contract).
            // With excludeStationsWithoutBikes=false a pinned departure is servable like an arrival (spec 13.1).
            boolean usable = rental && requireBikes ? fresh(pinned, now) : returnable(pinned, now);
            if (!usable) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "지정한 대여소의 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색하거나 다른 대여소를 선택해 주세요.");
            if (rental && requireBikes && pinned.availableBikeCount() == 0) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "PINNED_STATION_EMPTY", "지정한 대여소에 대여 가능한 자전거가 없어요. 자동 선택하거나 다른 대여소를 지정해 주세요.");
            return List.of(pinned);
        }
        int radius = options.stationSearchRadiusMeters();
        String label = distanceLabel(radius);
        var nearby = stations.findNearby(location, radius);
        if (rental && requireBikes) {
            var usable = nearby.stream().filter(s -> fresh(s, now) && s.availableBikeCount() > 0)
                    .sorted(Comparator.comparingDouble(s -> location.distanceTo(s.location()))).toList();
            if (usable.isEmpty() && !nearby.isEmpty()) {
                // Review M3(b), spec 11.0 decision table: "no bikes" (NO_RENTAL_BIKES -> 422) only when every
                // candidate is FRESH with 0 bikes. If any candidate is STALE/UNAVAILABLE its stock is unknown, so
                // the answer is 503 (STATION_STATUS_UNAVAILABLE -> public REALTIME_DATA_UNAVAILABLE), not "no bikes".
                if (nearby.stream().allMatch(s -> fresh(s, now)))
                    throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_RENTAL_BIKES", "출발지 " + label + " 안에 대여소는 있지만 대여 가능한 자전거가 확인되지 않았어요. 출발 장소를 변경하거나 다른 대여소를 지정해 주세요.");
                throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "출발지 주변 대여소는 있지만 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색해 주세요.");
            }
            return nearest(location, usable, radius);
        }
        // Review M3(a): return candidates exclude only UNAVAILABLE stations; STALE ones stay (spec 11.0). The same
        // rule applies to departures with excludeStationsWithoutBikes=false (public STALE and 0-bike stations).
        var usable = nearby.stream().filter(s -> returnable(s, now))
                .sorted(Comparator.comparingDouble(s -> location.distanceTo(s.location()))).toList();
        if (usable.isEmpty() && !nearby.isEmpty())
            throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", (rental ? "출발지" : "목적지") + " 주변 대여소는 있지만 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색해 주세요.");
        return nearest(location, usable, radius);
    }
    /** Phase 3 decision 3: stations within min(500 m, radius) first; otherwise the whole radius; at most n. */
    private List<Station> nearest(Location location, List<Station> usable, int radius) {
        double preferred = Math.min(WITHIN_PREFERRED_METERS, radius);
        var distinct = usable.stream().filter(distinctIds()).toList();
        var within = distinct.stream().filter(s -> location.distanceTo(s.location()) <= preferred).toList();
        return (within.isEmpty() ? distinct : within).stream().limit(maxStationCandidates).toList();
    }
    private static java.util.function.Predicate<Station> distinctIds() {
        var seen = new java.util.HashSet<String>();
        return station -> seen.add(station.stationId());
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
