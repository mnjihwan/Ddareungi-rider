package com.ddareungi.place.infrastructure;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.ProviderCallException;
import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.place.Place;
import com.ddareungi.place.PlaceProviderException;
import com.ddareungi.place.PlaceSearchProvider;
import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;

/** Kakao Local implementation. Any provider or parsing failure becomes a sanitized {@link PlaceProviderException}. */
@Component
public class KakaoPlaceSearchProvider implements PlaceSearchProvider {
    static final String BASE_URL = "https://dapi.kakao.com";
    private static final Pattern PLACE_URL = Pattern.compile("https?://place\\.map\\.kakao\\.com/[0-9]+");
    private static final Pattern HTTP_SCHEME = Pattern.compile("^http:");

    private final RestClient client;
    private final JsonMapper mapper;
    private final String key;
    private final ProviderResilience resilience;

    /** Uses the builder's own request factory and fresh default resilience state (tests, manual wiring). */
    public KakaoPlaceSearchProvider(RestClient.Builder builder, JsonMapper mapper, String key) {
        this(builder, mapper, key, ProviderResilience.defaults());
    }

    public KakaoPlaceSearchProvider(RestClient.Builder builder, JsonMapper mapper, String key,
            ProviderResilience resilience) {
        this.client = builder.baseUrl(BASE_URL).build();
        this.mapper = mapper;
        this.key = key.trim();
        this.resilience = resilience;
    }

    // Production wiring: place-specific connect/read timeouts (D8) and the shared place breaker/bulkhead.
    @Autowired
    public KakaoPlaceSearchProvider(RestClient.Builder builder, JsonMapper mapper,
            @Value("${kakao.maps.rest-api-key:}") String key, ProviderHttpProperties http, ProviderResilience resilience) {
        this(builder.requestFactory(http.place().requestFactory()), mapper, key, resilience);
    }

    @Override public boolean configured() { return !key.isBlank(); }

    @Override
    public List<Place> keyword(String query, @Nullable Location center, @Nullable String categoryGroupCode) {
        return parse("Invalid place search response", fetch(uri -> {
            uri.path("/v2/local/search/keyword.json").queryParam("query", "{query}").queryParam("size", 15);
            if (center != null) uri.queryParam("x", center.longitude()).queryParam("y", center.latitude())
                    .queryParam("sort", "distance");
            if (categoryGroupCode != null) uri.queryParam("category_group_code", categoryGroupCode);
            return uri.build(query);
        }), documents -> {
            var result = new ArrayList<Place>();
            for (var item : documents) {
                if (categoryGroupCode == null || categoryGroupCode.equals(item.path("category_group_code").asString(""))) {
                    result.add(place(item));
                }
            }
            return result;
        });
    }

    @Override
    public List<Place> address(String query) {
        return parse("Invalid address response", fetch(uri -> uri.path("/v2/local/search/address.json")
                .queryParam("query", "{query}").queryParam("size", 5).build(query)), documents -> {
            var result = new ArrayList<Place>();
            for (var address : documents) {
                String name = address.path("address_name").asString("");
                result.add(new Place(name, name, new Location(Double.parseDouble(address.path("y").asString()),
                        Double.parseDouble(address.path("x").asString())), null, ""));
            }
            return result;
        });
    }

    @Override
    public @Nullable Place reverse(Location location) {
        return parse("Invalid address response", fetch(uri -> uri.path("/v2/local/geo/coord2address.json")
                .queryParam("x", location.longitude()).queryParam("y", location.latitude())
                .queryParam("input_coord", "WGS84").build()), documents -> {
            for (var document : documents) {
                var road = document.path("road_address");
                String address = road.path("address_name").asString("");
                if (address.isBlank()) address = document.path("address").path("address_name").asString("");
                if (address.isBlank()) continue;
                String building = road.path("building_name").asString("");
                return new Place(building.isBlank() ? address : building, address, location, null, "");
            }
            return null;
        });
    }

    @Override
    public List<Place> category(String categoryGroupCode, Location center, int radiusMeters) {
        return parse("Invalid category response", fetch(uri -> uri.path("/v2/local/search/category.json")
                .queryParam("category_group_code", categoryGroupCode)
                .queryParam("x", center.longitude()).queryParam("y", center.latitude())
                .queryParam("radius", radiusMeters).queryParam("sort", "distance").queryParam("size", 15).build()),
                documents -> {
                    var result = new ArrayList<Place>();
                    for (var item : documents) {
                        if (categoryGroupCode.equals(item.path("category_group_code").asString(""))) result.add(place(item));
                    }
                    return result;
                });
    }

    /**
     * One GET to Kakao Local under the place deadline, breaker, bulkhead and single retry. Provider bodies (which may
     * echo credentials) are never kept.
     */
    @Nullable String fetch(Function<UriBuilder, URI> uri) {
        if (!configured()) throw new PlaceProviderException("Place provider is not configured", false);
        try {
            return resilience.place().call(() -> client.get().uri(uri).header("Authorization", "KakaoAK " + key)
                    .retrieve().body(String.class), true);
        } catch (ProviderCallException error) {
            throw new PlaceProviderException("Place provider call stopped: " + error.kind(),
                    error.kind() == ProviderCallException.Kind.TIMEOUT);
        } catch (RuntimeException error) {
            throw new PlaceProviderException("Place provider request failed", false);
        }
    }

    private <T> T parse(String invalid, @Nullable String body, Function<JsonNode, T> reader) {
        try {
            var documents = mapper.readTree(body == null ? "" : body).path("documents");
            if (!documents.isArray()) throw new IllegalStateException(invalid);
            return reader.apply(documents);
        } catch (RuntimeException error) {
            throw new PlaceProviderException(invalid, false);
        }
    }

    private static Place place(JsonNode item) {
        var location = new Location(Double.parseDouble(item.path("y").asString()), Double.parseDouble(item.path("x").asString()));
        String address = item.path("road_address_name").asString("");
        if (address.isBlank()) address = item.path("address_name").asString("");
        String name = item.path("place_name").asString("");
        if (name.isBlank()) throw new IllegalStateException("Invalid place name");
        String sourceUrl = item.path("place_url").asString("");
        sourceUrl = PLACE_URL.matcher(sourceUrl).matches() ? HTTP_SCHEME.matcher(sourceUrl).replaceFirst("https:") : null;
        return new Place(name, address, location, sourceUrl, item.path("category_group_code").asString(""));
    }
}
