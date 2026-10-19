package com.ddareungi.place;

import com.ddareungi.global.response.ApiResponse;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.station.domain.KoreanSearch;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/v1/places")
public class PlaceSearchController {
    private final RestClient client;
    private final JsonMapper mapper;
    private final String key;
    private final StationSearchPort stations;

    public PlaceSearchController(RestClient.Builder builder, JsonMapper mapper,
            @Value("${kakao.maps.rest-api-key:}") String key, StationSearchPort stations) {
        this.client = builder.baseUrl("https://dapi.kakao.com").build();
        this.mapper = mapper; this.key = key.trim(); this.stations = stations;
    }

    @GetMapping("/reverse")
    public ApiResponse<Place> reverse(@RequestParam double latitude, @RequestParam double longitude) {
        var location = new Location(latitude, longitude);
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "주소 조회를 현재 사용할 수 없습니다.");
        try {
            String body = client.get().uri(uri -> uri.path("/v2/local/geo/coord2address.json")
                    .queryParam("x", longitude).queryParam("y", latitude).queryParam("input_coord", "WGS84").build())
                    .header("Authorization", "KakaoAK " + key).retrieve().body(String.class);
            var documents = mapper.readTree(body).path("documents");
            if (!documents.isArray()) throw new IllegalStateException("Invalid address response");
            for (var document : documents) {
                var road = document.path("road_address");
                String address = road.path("address_name").asString("");
                if (address.isBlank()) address = document.path("address").path("address_name").asString("");
                if (address.isBlank()) continue;
                String building = road.path("building_name").asString("");
                return ApiResponse.ok(new Place(building.isBlank() ? address : building, address, location, null, ""));
            }
            return ApiResponse.ok(null);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "주소를 가져오지 못했습니다.");
        }
    }

    @GetMapping("/search")
    public ApiResponse<List<Place>> search(@RequestParam String query,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude) {
        String term = query.trim();
        if (term.length() < 2 || term.length() > 100) throw new IllegalArgumentException("검색어는 2~100자로 입력해 주세요.");
        if ((latitude == null) != (longitude == null)) throw new IllegalArgumentException("검색 중심의 위도와 경도를 함께 입력해 주세요.");
        if (latitude != null) new Location(latitude, longitude);
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "장소 검색을 현재 사용할 수 없습니다.");
        try {
            var result = keywordPlaces(term, latitude, longitude, false);
            // Preserve real English place searches; retry a complete Korean keyboard conversion only if unrelated.
            String converted = KoreanSearch.koreanKeyboardQuery(term);
            if (!converted.equals(term) && result.stream().noneMatch(place ->
                    KoreanSearch.bestScore(place.name(), place.address(), term) >= 0)) {
                try { result.addAll(keywordPlaces(converted, latitude, longitude, false)); }
                catch (Exception ignored) { /* Keep original results and station completion available. */ }
            }
            // Nearby businesses can fill the general first page before a matching station.
            // Query the provider's subway category separately, then merge by place identity.
            var subways = new ArrayList<Place>();
            try { subways.addAll(keywordPlaces(term, latitude, longitude, true)); }
            catch (Exception ignored) { /* General results still work if the category lookup fails. */ }
            subways.addAll(result.stream().filter(place -> "SW8".equals(place.categoryGroupCode())).toList());
            subways.removeIf(place -> !KoreanSearch.matches(place.name(), term));
            if (subways.isEmpty()) {
                // Complete partial subway names only from rental stations we actually have.
                // Then resolve their real location/category through Kakao, never invent coordinates.
                try {
                    var center = latitude == null ? new Location(37.5665, 126.9780) : new Location(latitude, longitude);
                    var names = new java.util.LinkedHashSet<String>();
                    var pattern = java.util.regex.Pattern.compile("([가-힣A-Za-z0-9·.]+역)");
                    for (var station : stations.searchByName(term, center)) {
                        if (station.stationName() == null || station.stationName().isBlank()) continue;
                        var matcher = pattern.matcher(station.stationName().replaceFirst("^\\s*[0-9]+\\.\\s*", ""));
                        if (matcher.find()) {
                            String name = matcher.group(1);
                            if (KoreanSearch.matches(name, term) && !normalized(name).equals(normalized(term))) names.add(name);
                        }
                    }
                    for (String name : names.stream().limit(2).toList()) {
                        try { subways.addAll(keywordPlaces(name, latitude, longitude, true).stream()
                                .filter(place -> KoreanSearch.matches(place.name(), term)).toList()); }
                        catch (Exception ignored) { /* Preserve the original successful search results. */ }
                    }
                } catch (Exception ignored) { /* Rental station lookup is an optional completion source. */ }
            }
            var distinct = new java.util.LinkedHashMap<String, Place>();
            java.util.stream.Stream.concat(subways.stream(), result.stream()).forEach(place ->
                    distinct.putIfAbsent(place.sourceUrl() != null ? place.sourceUrl() :
                            place.name() + "|" + place.location().latitude() + "|" + place.location().longitude(), place));
            result = new ArrayList<>(distinct.values());
            if (result.isEmpty()) {
                String addressBody = client.get().uri(uri -> uri.path("/v2/local/search/address.json")
                        .queryParam("query", "{query}").queryParam("size", 5).build(term))
                        .header("Authorization", "KakaoAK " + key).retrieve().body(String.class);
                var addresses = mapper.readTree(addressBody).path("documents");
                if (!addresses.isArray()) throw new IllegalStateException("Invalid address response");
                for (var address : addresses) {
                    String name = address.path("address_name").asString("");
                    result.add(new Place(name, name, new Location(Double.parseDouble(address.path("y").asString()),
                            Double.parseDouble(address.path("x").asString())), null, ""));
                }
            }
            return ApiResponse.ok(result);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "장소 검색 응답을 가져오지 못했습니다.");
        }
    }

    private static String normalized(String value) { return value.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT); }

    private ArrayList<Place> keywordPlaces(String term, Double latitude, Double longitude, boolean subwaysOnly) throws Exception {
        String body = client.get().uri(uri -> {
            uri.path("/v2/local/search/keyword.json").queryParam("query", "{query}").queryParam("size", 15);
            if (latitude != null) uri.queryParam("x", longitude).queryParam("y", latitude).queryParam("sort", "distance");
            if (subwaysOnly) uri.queryParam("category_group_code", "SW8");
            return uri.build(term);
        }).header("Authorization", "KakaoAK " + key).retrieve().body(String.class);
        var items = mapper.readTree(body).path("documents");
        if (!items.isArray()) throw new IllegalStateException("Invalid place search response");
        var result = new ArrayList<Place>();
        for (var item : items) {
            if (!subwaysOnly || "SW8".equals(item.path("category_group_code").asString(""))) result.add(place(item));
        }
        return result;
    }

    @GetMapping("/nearby-subways")
    public ApiResponse<List<Place>> nearbySubways(@RequestParam double latitude, @RequestParam double longitude) {
        var center = new Location(latitude, longitude);
        if (key.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "주변 지하철역을 현재 조회할 수 없습니다.");
        try {
            String body = client.get().uri(uri -> uri.path("/v2/local/search/category.json")
                    .queryParam("category_group_code", "SW8").queryParam("x", longitude).queryParam("y", latitude)
                    .queryParam("radius", 2000).queryParam("sort", "distance").queryParam("size", 15).build())
                    .header("Authorization", "KakaoAK " + key).retrieve().body(String.class);
            var items = mapper.readTree(body).path("documents");
            if (!items.isArray()) throw new IllegalStateException();
            var distinct = new java.util.LinkedHashMap<String, Place>();
            for (var item : items) {
                if (!item.path("category_group_code").asString("").equals("SW8")) continue;
                var station = place(item);
                if (center.distanceTo(station.location()) > 2000) continue;
                // Multiple subway lines at one station should occupy one suggestion.
                String name = station.name().replaceFirst("(역)(?:\\s|[0-9]+호선).*", "$1");
                distinct.putIfAbsent(name, station);
            }
            return ApiResponse.ok(distinct.values().stream()
                    .sorted(java.util.Comparator.comparingDouble(station -> center.distanceTo(station.location())))
                    .limit(3).toList());
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "주변 지하철역 정보를 가져오지 못했습니다.");
        }
    }

    private static Place place(tools.jackson.databind.JsonNode item) {
        var location = new Location(Double.parseDouble(item.path("y").asString()), Double.parseDouble(item.path("x").asString()));
        String address = item.path("road_address_name").asString("");
        if (address.isBlank()) address = item.path("address_name").asString("");
        String name = item.path("place_name").asString("");
        if (name.isBlank()) throw new IllegalStateException("Invalid place name");
        String sourceUrl = item.path("place_url").asString("");
        if (!sourceUrl.matches("https?://place\\.map\\.kakao\\.com/[0-9]+")) sourceUrl = null;
        else sourceUrl = sourceUrl.replaceFirst("^http:", "https:");
        return new Place(name, address, location, sourceUrl, item.path("category_group_code").asString(""));
    }

    public record Place(String name, String address, Location location, String sourceUrl, String categoryGroupCode) {}
}
