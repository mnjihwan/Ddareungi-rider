package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

@Component
@ConditionalOnProperty(name = "routing.provider", havingValue = "kakao", matchIfMissing = true)
public class KakaoRouteClient implements RouteProvider {
    private final RestClient client;
    private final JsonMapper mapper;
    private final String key;

    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper,
            @Value("${kakao.maps.rest-api-key:}") String key) {
        this.client = builder.baseUrl("https://dapi.kakao.com").build();
        this.mapper = mapper;
        this.key = key.trim();
    }
    @Override public boolean configured() { return !key.isBlank(); }
    @Override public String attribution() { return "경로 제공 · Kakao Maps"; }

    // This object exists only for one planning request. No cross-request cache or storage.
    @Override public RouteProvider forRequest() {
        return new RouteProvider() {
            private final HashMap<Pair, Leg> legs = new HashMap<>();
            @Override public boolean configured() { return KakaoRouteClient.this.configured(); }
            @Override public String attribution() { return KakaoRouteClient.this.attribution(); }
            @Override public Leg directions(String profile, Location from, Location to) {
                var pair = new Pair(profile, from, to);
                if (!legs.containsKey(pair)) {
                    try { legs.put(pair, KakaoRouteClient.this.directions(profile, from, to)); }
                    catch (RouteException error) {
                        if (!error.code.equals("NO_ROAD_ROUTE")) throw error;
                        legs.put(pair, null);
                    }
                }
                var leg = legs.get(pair);
                if (leg == null) throw noRoad();
                return leg;
            }
            @Override public Double[][] durations(String profile, List<Location> sources, List<Location> destinations) {
                return matrix(this, profile, sources, destinations);
            }
        };
    }
    private record Pair(String profile, Location from, Location to) {}
    @Override public Double[][] durations(String profile, List<Location> sources, List<Location> destinations) {
        return matrix(this, profile, sources, destinations);
    }
    private static Double[][] matrix(RouteProvider provider, String profile, List<Location> sources, List<Location> destinations) {
        var result = new Double[sources.size()][destinations.size()];
        for (int i = 0; i < sources.size(); i++) for (int j = 0; j < destinations.size(); j++) {
            try { result[i][j] = provider.directions(profile, sources.get(i), destinations.get(j)).durationSeconds(); }
            catch (RouteException error) { if (!error.code.equals("NO_ROAD_ROUTE")) throw error; }
        }
        return result;
    }

    @Override public Leg directions(String profile, Location from, Location to) {
        boolean walk = switch (profile) {
            case "foot-walking" -> true;
            case "cycling-regular" -> false;
            default -> throw new IllegalArgumentException("지원하지 않는 이동 방식입니다.");
        };
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요.");
        if (from.distanceTo(to) < 0.5) return new Leg(walk ? "walk" : "bicycle", 0, 0, List.of(from, to), List.of());
        JsonNode body;
        try {
            var response = client.get().uri(uri -> uri.path(walk ? "/v2/routing/walk" : "/v2/routing/bicycle")
                    .queryParam("start_x", from.longitude()).queryParam("start_y", from.latitude())
                    .queryParam("end_x", to.longitude()).queryParam("end_y", to.latitude())
                    .queryParam("input_coord", "WGS84").queryParam("output_coord", "WGS84")
                    .queryParam("route_mode", walk ? "BROAD_FIRST" : "BIKE_ONLY").build())
                    .header("Authorization", "KakaoAK " + key).retrieve().body(String.class);
            if (response == null || response.isBlank()) throw new IllegalStateException();
            body = mapper.readTree(response);
            if (body == null || !body.isObject()) throw new IllegalStateException();
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 429) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_LIMIT", "길찾기 이용량이 많아요. 잠시 후 다시 시도해 주세요.");
            if (status == 401 || status == 403) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_AUTH", "길찾기 연결을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.");
            throw unavailable();
        } catch (RuntimeException error) { throw unavailable(); }

        String status = body.path("status").asString("");
        if (List.of("START_LINK_NOT_FOUND", "END_LINK_NOT_FOUND", "ROUTE_RESULT_NOT_FOUND", "TOO_FAR_AWAY", "TOO_MANY_SEARCH_LINK").contains(status)) throw noRoad();
        if (!status.equals("OK")) throw unavailable();
        try {
            var route = body.path("route");
            var path = new ArrayList<Location>();
            var steps = new ArrayList<Step>();
            if (!route.path("legs").isArray()) throw new IllegalStateException();
            for (var leg : route.path("legs")) {
                if (!leg.path("steps").isArray()) throw new IllegalStateException();
                for (var step : leg.path("steps")) {
                    var properties = step.path("properties");
                    steps.add(new Step(properties.path("guidance").asString("다음 안내 지점으로 이동하세요"),
                            number(properties.path("distance")), number(properties.path("time"))));
                    var points = step.path("path").path("points");
                    if (!points.isArray()) throw new IllegalStateException();
                    for (var point : points) {
                        if (!point.isArray() || point.size() != 2) throw new IllegalStateException();
                        var location = new Location(finite(point.get(1)), finite(point.get(0)));
                        if (path.isEmpty() || !path.getLast().equals(location)) path.add(location);
                    }
                }
            }
            // Routes may snap to nearby road entrances; reject unrelated geometry.
            if (path.size() < 2 || path.getFirst().distanceTo(from) > 150 || path.getLast().distanceTo(to) > 150) throw new IllegalStateException();
            return new Leg(walk ? "walk" : "bicycle", number(route.path("properties").path("totalDistance")),
                    number(route.path("properties").path("totalTime")), List.copyOf(path), List.copyOf(steps));
        } catch (RuntimeException error) { throw unavailable(); }
    }
    private static double finite(JsonNode node) {
        if (!node.isNumber() || !Double.isFinite(node.asDouble())) throw new IllegalStateException();
        return node.asDouble();
    }
    private static double number(JsonNode node) {
        double value = finite(node); if (value < 0) throw new IllegalStateException(); return value;
    }
    private static RouteException noRoad() {
        return new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_ROAD_ROUTE", "연결되는 도보·자전거 경로를 찾지 못했어요. 다른 출발·도착 장소를 선택해 주세요.");
    }
    private static RouteException unavailable() {
        return new RouteException(HttpStatus.BAD_GATEWAY, "ROUTING_UNAVAILABLE", "경로를 가져오지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
