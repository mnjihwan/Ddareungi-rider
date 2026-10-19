package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Component
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "routing.provider", havingValue = "ors")
public class OpenRouteServiceClient implements RouteProvider {
    private final RestClient client;
    private final JsonMapper mapper;
    private final String key;

    public OpenRouteServiceClient(RestClient.Builder builder, JsonMapper mapper,
            @Value("${routing.ors.api-key:}") String key) {
        // Current HeiGIT endpoint; the old api.openrouteservice.org URL is deprecated.
        client = builder.baseUrl("https://api.heigit.org/openrouteservice/v2").build();
        this.mapper = mapper; this.key = key.trim();
    }
    @Override public boolean configured() { return !key.isBlank(); }

    @Override public Double[][] durations(String profile, List<Location> sources, List<Location> destinations) {
        validateProfile(profile);
        var locations = new ArrayList<Location>(sources); locations.addAll(destinations);
        var json = request("/matrix/" + profile, Map.of("locations", coordinates(locations),
                "sources", IntStream.range(0, sources.size()).mapToObj(Integer::toString).toList(),
                "destinations", IntStream.range(sources.size(), locations.size()).mapToObj(Integer::toString).toList(),
                "metrics", List.of("duration"))).path("durations");
        try {
            if (!json.isArray() || json.size() != sources.size()) throw new IllegalStateException();
            var result = new Double[sources.size()][destinations.size()];
            for (int i = 0; i < sources.size(); i++) {
                if (!json.get(i).isArray() || json.get(i).size() != destinations.size()) throw new IllegalStateException();
                for (int j = 0; j < destinations.size(); j++) {
                    var entry = json.get(i).get(j);
                    result[i][j] = entry.isNull() ? null : number(entry);
                }
            }
            return result;
        } catch (RuntimeException error) { throw invalidResponse(); }
    }

    @Override public Leg directions(String profile, Location from, Location to) {
        validateProfile(profile);
        var response = request("/directions/" + profile + "/geojson", Map.of(
                "coordinates", coordinates(List.of(from, to)), "language", "en", "instructions", true,
                "radiuses", List.of(50, 50)));
        try {
            var feature = response.path("features").get(0);
            if (feature == null || !"LineString".equals(feature.path("geometry").path("type").asString())) throw new IllegalStateException();
            var path = new ArrayList<Location>();
            for (var point : feature.path("geometry").path("coordinates")) {
                if (!point.isArray() || point.size() < 2) throw new IllegalStateException();
                path.add(new Location(finite(point.get(1)), finite(point.get(0))));
            }
            if (path.size() < 2 || path.getFirst().distanceTo(from) > 60 || path.getLast().distanceTo(to) > 60)
                throw new IllegalStateException();
            var properties = feature.path("properties");
            var steps = new ArrayList<Step>();
            for (var segment : properties.path("segments")) for (var step : segment.path("steps")) {
                steps.add(new Step(koreanInstruction(step), number(step.path("distance")), number(step.path("duration"))));
            }
            return new Leg(profile.equals("foot-walking") ? "walk" : "bicycle",
                    number(properties.path("summary").path("distance")),
                    number(properties.path("summary").path("duration")), List.copyOf(path), List.copyOf(steps));
        } catch (RuntimeException error) { throw invalidResponse(); }
    }

    private JsonNode request(String path, Object body) {
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요. 잠시 후 다시 이용해 주세요.");
        try {
            String response = client.post().uri(path).header("Authorization", key)
                    .contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(String.class);
            return mapper.readTree(response);
        } catch (RestClientResponseException error) {
            int status = error.getStatusCode().value();
            if (status == 429 || status == 403) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_LIMIT", "길찾기 요청이 많거나 연결을 사용할 수 없어요. 잠시 후 다시 시도해 주세요.");
            if (status == 400 || status == 404) throw new RouteException(HttpStatus.UNPROCESSABLE_ENTITY, "NO_ROAD_ROUTE", "이 위치까지 연결되는 도보·자전거 경로를 찾지 못했어요. 가까운 도로나 다른 출발·도착 장소를 선택해 주세요.");
            throw invalidResponse();
        } catch (RuntimeException error) { throw invalidResponse(); }
        // Provider bodies and causes may contain credentials; never forward them.
    }
    private static List<List<Double>> coordinates(List<Location> locations) {
        return locations.stream().map(p -> List.of(p.longitude(), p.latitude())).toList();
    }
    private static String koreanInstruction(JsonNode step) {
        // ORS does not accept "ko". Translate its documented maneuver codes,
        // preserving road names instead of guessing directions from English text.
        String action = switch (step.path("type").asInt(-1)) {
            case 0 -> "좌회전하세요"; case 1 -> "우회전하세요";
            case 2 -> "크게 좌회전하세요"; case 3 -> "크게 우회전하세요";
            case 4 -> "왼쪽으로 완만하게 이동하세요"; case 5 -> "오른쪽으로 완만하게 이동하세요";
            case 6 -> "직진하세요"; case 7 -> "회전교차로에 진입하세요";
            case 8 -> "회전교차로에서 나가세요"; case 9 -> "유턴하세요";
            case 10 -> "구간 도착지에 도착합니다"; case 11 -> "출발하세요";
            case 12 -> "왼쪽 길을 유지하세요"; case 13 -> "오른쪽 길을 유지하세요";
            default -> step.path("instruction").asString("다음 안내 지점으로 이동하세요");
        };
        int exit = step.path("exit_number").asInt(0);
        if (step.path("type").asInt(-1) == 7 && exit > 0) action = "회전교차로의 " + exit + "번째 출구로 나가세요";
        String name = step.path("name").asString("").trim();
        return name.isBlank() || name.equals("-") ? action : name + " · " + action;
    }
    private static double finite(JsonNode value) {
        if (!value.isNumber() || !Double.isFinite(value.asDouble())) throw new IllegalStateException();
        return value.asDouble();
    }
    private static double number(JsonNode value) {
        double result = finite(value); if (result < 0) throw new IllegalStateException(); return result;
    }
    private static void validateProfile(String profile) {
        if (!List.of("foot-walking", "cycling-regular").contains(profile)) throw new IllegalArgumentException("지원하지 않는 이동 방식입니다.");
    }
    private static RouteException invalidResponse() {
        return new RouteException(HttpStatus.BAD_GATEWAY, "ROUTING_UNAVAILABLE", "경로를 가져오지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
