package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
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
    private final RouteProvider provider;
    private final boolean enabled;
    public PlanBikeRoute(StationSearchPort stations, RouteProvider provider, @Value("${routing.enabled:true}") boolean enabled) {
        this.stations = stations; this.provider = provider; this.enabled = enabled;
    }
    public boolean configured() { return enabled && provider.configured(); }

    public Plan execute(Location origin, Location destination, String departureStationId) {
        return execute(origin, destination, departureStationId, null);
    }

    public Plan execute(Location origin, Location destination, String departureStationId, String arrivalStationId) {
        if (origin == null || destination == null) throw new IllegalArgumentException("출발지와 목적지를 선택해 주세요.");
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요. 장소와 대여소 조회는 계속 이용할 수 있어요.");
        var departures = candidates(origin, true, departureStationId);
        var arrivals = candidates(destination, false, arrivalStationId);
        if (departures.isEmpty()) throw new RouteException(HttpStatus.NOT_FOUND, "NO_RENTAL_STATION", "출발지 1km 안에 대여 가능한 자전거가 확인된 대여소가 없어요. 출발지나 지정 대여소를 변경해 주세요.");
        if (arrivals.isEmpty()) throw new RouteException(HttpStatus.NOT_FOUND, "NO_RETURN_STATION", "목적지 1km 안에 최근 정보가 확인된 대여소가 없어요. 목적지를 변경해 주세요.");
        var starts = departures.stream().map(Station::location).toList();
        var ends = arrivals.stream().map(Station::location).toList();
        var routes = provider.forRequest();
        var first = routes.durations("foot-walking", List.of(origin), starts);
        var last = routes.durations("foot-walking", ends, List.of(destination));
        var cycle = routes.durations("cycling-regular", starts, ends);
        int startIndex = -1, endIndex = -1; double best = Double.POSITIVE_INFINITY;
        for (int i = 0; i < starts.size(); i++) for (int j = 0; j < ends.size(); j++) {
            if (departures.get(i).stationId().equals(arrivals.get(j).stationId())) continue;
            if (first[0][i] == null || last[j][0] == null || cycle[i][j] == null) continue;
            double seconds = first[0][i] + cycle[i][j] + last[j][0];
            if (seconds < best) { best = seconds; startIndex = i; endIndex = j; }
        }
        if (startIndex < 0) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_CONNECTED_ROUTE", "주변 대여소를 연결하는 도보·자전거 경로를 찾지 못했어요. 출발지나 목적지를 변경해 주세요.");
        var departure = departures.get(startIndex); var arrival = arrivals.get(endIndex);
        var legs = List.of(routes.directions("foot-walking", origin, departure.location()),
                routes.directions("cycling-regular", departure.location(), arrival.location()),
                routes.directions("foot-walking", arrival.location(), destination));
        // Check again after external routing calls, when stock may have changed.
        departure = stations.findByIds(List.of(departure.stationId()), origin).stream()
                .filter(s -> fresh(s) && s.availableBikeCount() > 0).findFirst()
                .orElseThrow(() -> new RouteException(HttpStatus.CONFLICT, "RENTAL_STOCK_CHANGED", "대여소 수량이 바뀌었어요. 경로를 다시 검색해 주세요."));
        return new Plan(origin, destination, departure, arrival, legs,
                legs.stream().mapToDouble(RouteProvider.Leg::distanceMeters).sum(),
                legs.stream().mapToDouble(RouteProvider.Leg::durationSeconds).sum(), Instant.now(),
                routes.attribution());
    }
    private List<Station> candidates(Location location, boolean rental, String pinnedId) {
        if (pinnedId != null && !pinnedId.isBlank()) {
            var pinned = stations.findByIds(List.of(pinnedId), location).stream()
                    .filter(s -> s.stationId().equals(pinnedId)).findFirst()
                    .orElseThrow(() -> new RouteException(HttpStatus.NOT_FOUND, "PINNED_STATION_NOT_FOUND", "지정한 대여소를 찾지 못했어요. 자동 선택하거나 다른 대여소를 지정해 주세요."));
            if (!fresh(pinned)) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", "지정한 대여소의 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색하거나 다른 대여소를 선택해 주세요.");
            if (rental && pinned.availableBikeCount() == 0) throw new RouteException(HttpStatus.CONFLICT, "PINNED_STATION_EMPTY", "지정한 대여소에 대여 가능한 자전거가 없어요. 자동 선택하거나 다른 대여소를 지정해 주세요.");
            return List.of(pinned);
        }
        var nearby = stations.findNearby(location, 1000);
        if (!nearby.isEmpty() && nearby.stream().noneMatch(PlanBikeRoute::fresh))
            throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "STATION_STATUS_UNAVAILABLE", (rental ? "출발지" : "목적지") + " 주변 대여소는 있지만 최신 수량을 확인하지 못했어요. 잠시 후 다시 검색해 주세요.");
        var usable = nearby.stream().filter(PlanBikeRoute::fresh)
                .filter(s -> !rental || s.availableBikeCount() > 0)
                .sorted(Comparator.comparingDouble(s -> location.distanceTo(s.location()))).toList();
        if (rental && usable.isEmpty() && !nearby.isEmpty())
            throw new RouteException(HttpStatus.NOT_FOUND, "NO_RENTAL_BIKES", "출발지 1km 안에 대여소는 있지만 대여 가능한 자전거가 확인되지 않았어요. 출발 장소를 변경하거나 다른 대여소를 지정해 주세요.");
        var within500 = usable.stream().filter(s -> location.distanceTo(s.location()) <= 500).toList();
        return (within500.isEmpty() ? usable : within500).stream().limit(3).toList();
    }
    private static boolean fresh(Station station) {
        var now = Instant.now();
        return station.location() != null && station.availableBikeCount() != null && station.availableBikeCount() >= 0
                && station.updatedAt() != null && !station.updatedAt().isBefore(now.minusSeconds(300))
                && !station.updatedAt().isAfter(now.plusSeconds(30));
    }
    public record Plan(Location origin, Location destination, Station departureStation, Station arrivalStation,
                       List<RouteProvider.Leg> legs, double distanceMeters, double durationSeconds,
                       Instant generatedAt, String attribution) {}
}
