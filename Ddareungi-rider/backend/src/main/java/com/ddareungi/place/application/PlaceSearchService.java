package com.ddareungi.place.application;

import com.ddareungi.global.exception.ApiException;
import com.ddareungi.global.exception.ErrorCode;
import com.ddareungi.place.Place;
import com.ddareungi.place.PlaceProviderException;
import com.ddareungi.place.PlaceSearchProvider;
import com.ddareungi.station.domain.KoreanSearch;
import com.ddareungi.station.domain.Location;
import com.ddareungi.station.domain.StationSearchPort;
import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.ContextPropagation;
import com.ddareungi.global.resilience.Deadline;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Place search use cases: merges general and subway results, removes duplicates and completes partial subway names
 * from rental station names. Supplementary lookups are best effort; only the primary lookup decides failure.
 */
@Service
public class PlaceSearchService {
    static final String SUBWAY = "SW8";
    static final int NEARBY_SUBWAY_RADIUS_METERS = 2000;
    private static final Location DEFAULT_CENTER = new Location(37.5665, 126.9780);
    private static final Pattern STATION_NUMBER_PREFIX = Pattern.compile("^\\s*[0-9]+\\.\\s*");
    private static final Pattern SUBWAY_NAME = Pattern.compile("([가-힣A-Za-z0-9·.]+역)");
    private static final Pattern SUBWAY_LINE_SUFFIX = Pattern.compile("(역)(?:\\s|[0-9]+호선).*");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private final PlaceSearchProvider provider;
    private final StationSearchPort stations;
    private final PlaceSearchCache cache;
    private final Duration totalTimeout;

    public PlaceSearchService(PlaceSearchProvider provider, StationSearchPort stations) {
        this(provider, stations, PlaceSearchCache.NONE);
    }

    public PlaceSearchService(PlaceSearchProvider provider, StationSearchPort stations, PlaceSearchCache cache) {
        this(provider, stations, cache, ProviderHttpProperties.defaults().place().totalTimeout());
    }

    /** @param totalTimeout budget of one place request across all provider calls and retries (D8: 3s) */
    public PlaceSearchService(PlaceSearchProvider provider, StationSearchPort stations, PlaceSearchCache cache,
                              Duration totalTimeout) {
        this.provider = provider;
        this.stations = stations;
        this.cache = cache;
        this.totalTimeout = totalTimeout;
    }

    /** Production wiring: the Redis cache exists only with the infra profile (D9: disabled locally). */
    @Autowired
    public PlaceSearchService(PlaceSearchProvider provider, StationSearchPort stations,
                              ObjectProvider<PlaceSearchCache> cache, ProviderHttpProperties http) {
        this(provider, stations, cache.getIfAvailable(() -> PlaceSearchCache.NONE), http.place().totalTimeout());
    }

    public @Nullable Place reverse(Location location) {
        return Deadline.after(totalTimeout).supply(() -> reverseWithinDeadline(location));
    }

    /** {@code term} is already trimmed and validated; {@code center} is optional. */
    public List<Place> search(String term, @Nullable Location center) {
        return Deadline.after(totalTimeout).supply(() -> searchWithinDeadline(term, center));
    }

    public List<Place> nearbySubways(Location center) {
        return Deadline.after(totalTimeout).supply(() -> nearbySubwaysWithinDeadline(center));
    }

    private @Nullable Place reverseWithinDeadline(Location location) {
        requireConfigured("주소 조회를 현재 사용할 수 없습니다.");
        try {
            return provider.reverse(location);
        } catch (PlaceProviderException error) {
            throw publicError(error, "주소를 가져오지 못했습니다.");
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            // Unexpected failures while merging stay a sanitized provider error, as before the split.
            throw new ApiException(ErrorCode.PLACE_PROVIDER_ERROR, "주소를 가져오지 못했습니다.");
        }
    }

    private List<Place> searchWithinDeadline(String term, @Nullable Location center) {
        requireConfigured("장소 검색을 현재 사용할 수 없습니다.");
        try {
            // Nearby businesses can fill the general first page before a matching station, so the provider's
            // subway category is queried separately. The two lookups are independent and run in parallel (D10);
            // everything that depends on their results below stays sequential.
            Future<List<Place>> subwayLookup = ContextPropagation.start("place-subway", () -> keyword(term, center, SUBWAY));
            ArrayList<Place> result;
            try {
                result = new ArrayList<>(keyword(term, center, null));
            } catch (RuntimeException error) {
                subwayLookup.cancel(true);
                throw error;
            }
            // Preserve real English place searches; retry a complete Korean keyboard conversion only if unrelated.
            String converted = KoreanSearch.koreanKeyboardQuery(term);
            if (!converted.equals(term) && result.stream().noneMatch(place ->
                    KoreanSearch.bestScore(place.name(), place.address(), term) >= 0)) {
                try { result.addAll(keyword(converted, center, null)); }
                catch (RuntimeException ignored) { /* Keep original results and station completion available. */ }
            }
            var subways = new ArrayList<Place>(optional(subwayLookup));
            subways.addAll(result.stream().filter(place -> SUBWAY.equals(place.categoryGroupCode())).toList());
            subways.removeIf(place -> !KoreanSearch.matches(place.name(), term));
            if (subways.isEmpty()) subways.addAll(completeSubwaysFromStations(term, center));
            var distinct = new LinkedHashMap<String, Place>();
            Stream.concat(subways.stream(), result.stream()).forEach(place -> distinct.putIfAbsent(identity(place), place));
            var merged = new ArrayList<>(distinct.values());
            if (merged.isEmpty()) merged.addAll(cached(PlaceSearchCache.key(term, null, "address"), () -> provider.address(term)));
            return merged;
        } catch (PlaceProviderException error) {
            throw publicError(error, "장소 검색 응답을 가져오지 못했습니다.");
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            // Unexpected failures while merging stay a sanitized provider error, as before the split.
            throw new ApiException(ErrorCode.PLACE_PROVIDER_ERROR, "장소 검색 응답을 가져오지 못했습니다.");
        }
    }

    private List<Place> nearbySubwaysWithinDeadline(Location center) {
        requireConfigured("주변 지하철역을 현재 조회할 수 없습니다.");
        try {
            var distinct = new LinkedHashMap<String, Place>();
            for (var station : cached(PlaceSearchCache.key("", center, "category:" + SUBWAY + ":" + NEARBY_SUBWAY_RADIUS_METERS),
                    () -> provider.category(SUBWAY, center, NEARBY_SUBWAY_RADIUS_METERS))) {
                if (center.distanceTo(station.location()) > NEARBY_SUBWAY_RADIUS_METERS) continue;
                // Multiple subway lines at one station should occupy one suggestion.
                distinct.putIfAbsent(SUBWAY_LINE_SUFFIX.matcher(station.name()).replaceFirst("$1"), station);
            }
            return distinct.values().stream()
                    .sorted(Comparator.comparingDouble(station -> center.distanceTo(station.location())))
                    .limit(3).toList();
        } catch (PlaceProviderException error) {
            throw publicError(error, "주변 지하철역 정보를 가져오지 못했습니다.");
        } catch (ApiException error) {
            throw error;
        } catch (RuntimeException error) {
            // Unexpected failures while merging stay a sanitized provider error, as before the split.
            throw new ApiException(ErrorCode.PLACE_PROVIDER_ERROR, "주변 지하철역 정보를 가져오지 못했습니다.");
        }
    }

    /**
     * Completes partial subway names only from rental stations we actually have, then resolves their real
     * location/category through the provider; coordinates are never invented.
     */
    private List<Place> completeSubwaysFromStations(String term, @Nullable Location center) {
        var found = new ArrayList<Place>();
        try {
            var names = new LinkedHashSet<String>();
            for (var station : stations.searchByName(term, center == null ? DEFAULT_CENTER : center)) {
                if (station.stationName() == null || station.stationName().isBlank()) continue;
                var matcher = SUBWAY_NAME.matcher(STATION_NUMBER_PREFIX.matcher(station.stationName()).replaceFirst(""));
                if (matcher.find()) {
                    String name = matcher.group(1);
                    if (KoreanSearch.matches(name, term) && !normalized(name).equals(normalized(term))) names.add(name);
                }
            }
            for (String name : names.stream().limit(2).toList()) {
                try {
                    found.addAll(keyword(name, center, SUBWAY).stream()
                            .filter(place -> KoreanSearch.matches(place.name(), term)).toList());
                } catch (RuntimeException ignored) { /* Preserve the original successful search results. */ }
            }
        } catch (RuntimeException ignored) { /* Rental station lookup is an optional completion source. */ }
        return found;
    }

    private List<Place> keyword(String query, @Nullable Location center, @Nullable String category) {
        return cached(PlaceSearchCache.key(query, center, category == null ? "keyword" : "keyword:" + category),
                () -> provider.keyword(query, center, category));
    }

    /** Provider answers are cached only on success; reverse geocoding is never cached (it echoes exact coordinates). */
    private List<Place> cached(String key, Supplier<List<Place>> lookup) {
        try {
            var hit = cache.get(key);
            if (hit != null) return hit;
        } catch (RuntimeException ignored) { /* Fail open: a cache error is a miss. */ }
        var places = List.copyOf(lookup.get());
        try { cache.put(key, places); }
        catch (RuntimeException ignored) { /* Fail open: the answer is still returned. */ }
        return places;
    }

    /** Result of a best-effort lookup; any failure (or interruption) yields no places. */
    private static List<Place> optional(Future<List<Place>> lookup) {
        try {
            return lookup.get();
        } catch (InterruptedException error) {
            lookup.cancel(true);
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | RuntimeException error) {
            return List.of();
        }
    }

    private void requireConfigured(String message) {
        if (!provider.configured()) throw new ApiException(ErrorCode.SERVICE_UNAVAILABLE, message);
    }

    private static ApiException publicError(PlaceProviderException error, String message) {
        // The provider failure is never attached as cause: it is internal and must not reach logs with bodies.
        return error.timeout() ? new ApiException(ErrorCode.EXTERNAL_API_TIMEOUT)
                : new ApiException(ErrorCode.PLACE_PROVIDER_ERROR, message);
    }

    private static String identity(Place place) {
        return place.sourceUrl() != null ? place.sourceUrl()
                : place.name() + "|" + place.location().latitude() + "|" + place.location().longitude();
    }

    private static String normalized(String value) {
        return WHITESPACE.matcher(value).replaceAll("").toLowerCase(Locale.ROOT);
    }
}
