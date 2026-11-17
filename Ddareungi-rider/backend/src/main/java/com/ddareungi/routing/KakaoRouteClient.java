package com.ddareungi.routing;

import com.ddareungi.global.http.ProviderHttpProperties;
import com.ddareungi.global.resilience.ContextPropagation;
import com.ddareungi.global.resilience.ProviderCallException;
import com.ddareungi.global.resilience.ProviderResilience;
import com.ddareungi.station.domain.Location;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

@Component
@ConditionalOnProperty(name = "routing.provider", havingValue = "kakao", matchIfMissing = true)
public class KakaoRouteClient implements RouteProvider {
    // Kakao has no matrix endpoint, so each candidate pair is one directions call.
    // Run them concurrently but cap in-flight requests to stay within provider quotas.
    static final int MAX_PARALLEL_CALLS = 6;
    /**
     * Review H1: provider answers that will not get better within the same request (quota exhausted, key refused).
     * After the first one, the request-scoped provider stops calling Kakao and fails the remaining cells at once.
     */
    static final java.util.Set<String> REQUEST_STOPPING_CODES = java.util.Set.of("ROUTING_LIMIT", "ROUTING_AUTH");
    public static final String DEFAULT_BASE_URL = "https://dapi.kakao.com";

    private final RestClient client;
    private final JsonMapper mapper;
    private final String key;
    private final ProviderResilience resilience;

    /** Uses the builder's own request factory and fresh default resilience state (tests, manual wiring). */
    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper, String key) {
        this(builder, mapper, key, ProviderResilience.defaults());
    }

    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper, String key, ProviderResilience resilience) {
        this(builder, mapper, key, resilience, DEFAULT_BASE_URL);
    }

    /** @param baseUrl {@code kakao.maps.base-url} (A6: externalised so a local stub can stand in for end-to-end runs) */
    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper, String key, ProviderResilience resilience,
                            String baseUrl) {
        this.client = builder.baseUrl(baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : baseUrl.trim()).build();
        this.mapper = mapper;
        this.key = key.trim();
        this.resilience = resilience;
    }

    /** Production wiring with the default base URL. */
    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper, String key, ProviderHttpProperties http,
                            ProviderResilience resilience) {
        this(builder, mapper, key, http, resilience, DEFAULT_BASE_URL);
    }

    // Production wiring: route-specific connect/read timeouts (D8) and the shared walking/bicycle breakers.
    @Autowired
    public KakaoRouteClient(RestClient.Builder builder, JsonMapper mapper,
            @Value("${kakao.maps.rest-api-key:}") String key, ProviderHttpProperties http, ProviderResilience resilience,
            @Value("${kakao.maps.base-url:" + DEFAULT_BASE_URL + "}") String baseUrl) {
        this(builder.requestFactory(http.route().requestFactory()), mapper, key, resilience, baseUrl);
    }
    @Override public boolean configured() { return !key.isBlank(); }
    @Override public String attribution() { return "경로 제공 · Kakao Maps"; }
    /** {@code route_mode=BIKE_ONLY} (bike roads preferred) or {@code SHORTEST}: see {@link #directions}. */
    @Override public boolean supportsBikeRoadPreference() { return true; }

    // This object exists only for one planning request. No cross-request cache or storage.
    @Override public RouteProvider forRequest() {
        return new RouteProvider() {
            // A future per pair lets concurrent matrix tasks share one in-flight call.
            // A completed null value means the provider confirmed there is no road route.
            private final ConcurrentHashMap<Pair, CompletableFuture<Leg>> legs = new ConcurrentHashMap<>();
            // Review H1: the first 429/401/403 of this request. Set before the failing call returns (and so before its
            // matrix permit is released), so every call started later sees it and makes no HTTP request. Only calls
            // already in flight at that moment (at most MAX_PARALLEL_CALLS per matrix) can still reach the provider.
            private final AtomicReference<RouteException> stopped = new AtomicReference<>();
            @Override public boolean configured() { return KakaoRouteClient.this.configured(); }
            @Override public String attribution() { return KakaoRouteClient.this.attribution(); }
            @Override public boolean supportsBikeRoadPreference() { return KakaoRouteClient.this.supportsBikeRoadPreference(); }
            @Override public Leg directions(String profile, Location from, Location to) {
                var pair = new Pair(profile, from, to);
                var created = new CompletableFuture<Leg>();
                // Legs already answered (or in flight) are still served after a stop; only new calls are refused.
                var existing = legs.get(pair);
                if (existing == null) {
                    var stop = stopped.get();
                    if (stop != null) throw copy(stop);
                    existing = legs.putIfAbsent(pair, created);
                }
                if (existing == null) {
                    existing = created;
                    try {
                        created.complete(KakaoRouteClient.this.directions(profile, from, to));
                    } catch (RouteException error) {
                        if (REQUEST_STOPPING_CODES.contains(error.code)) stopped.compareAndSet(null, error);
                        if (!error.code.equals("NO_ROAD_ROUTE")) {
                            // Transient failures are not cached; a later lookup may retry.
                            legs.remove(pair, created);
                            created.completeExceptionally(error);
                            throw error;
                        }
                        created.complete(null);
                    } catch (RuntimeException error) {
                        legs.remove(pair, created);
                        created.completeExceptionally(error);
                        throw error;
                    } finally {
                        // Never leave other waiters blocked, even on an Error.
                        if (!created.isDone()) {
                            legs.remove(pair, created);
                            created.completeExceptionally(new IllegalStateException("Route lookup aborted"));
                        }
                    }
                }
                var leg = join(existing);
                if (leg == null) throw noRoad();
                return leg;
            }
            @Override public RouteMatrix matrix(String profile, List<Location> sources, List<Location> destinations) {
                return KakaoRouteClient.matrix(this, profile, sources, destinations);
            }
        };
    }
    /** A fresh exception per refused cell, so cells never share (and mutate the cause of) one instance. */
    private static RouteException copy(RouteException error) {
        return new RouteException(error.status, error.code, error.getMessage());
    }
    // The profile carries the route mode (walk, BIKE_ONLY, SHORTEST), so the per-request cache never mixes modes.
    private record Pair(String profile, Location from, Location to) {}
    private record Pending(int row, int column, Future<RouteMatrix.Cell> cell) {}

    @Override public RouteMatrix matrix(String profile, List<Location> sources, List<Location> destinations) {
        return matrix(this, profile, sources, destinations);
    }

    /**
     * One directions call per pair. A6: a provider failure of one pair is recorded in its cell instead of cancelling
     * the comparison, so the search can still answer with the other pairs (spec 13.1 partial results). The number of
     * calls is at most one per pair (the caller keeps the 6 + 9 = 15 budget). Review H1: on the request-scoped provider,
     * a quota (429) or credential (401/403) failure stops the request: cells not yet started fail at once without a
     * call. Other failures (5xx after retry, timeouts, invalid answers) stay per cell and the other cells go on.
     */
    private static RouteMatrix matrix(RouteProvider provider, String profile, List<Location> sources, List<Location> destinations) {
        var result = new RouteMatrix.Cell[sources.size()][destinations.size()];
        int total = sources.size() * destinations.size();
        if (total == 0) return new RouteMatrix(result);
        if (total == 1) {
            result[0][0] = cell(provider, profile, sources.getFirst(), destinations.getFirst());
            return new RouteMatrix(result);
        }
        var permits = new Semaphore(MAX_PARALLEL_CALLS);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var cells = new ArrayList<Pending>(total);
            for (int i = 0; i < sources.size(); i++) for (int j = 0; j < destinations.size(); j++) {
                var from = sources.get(i);
                var to = destinations.get(j);
                // Carry the request id (MDC) and plan deadline into each virtual thread.
                cells.add(new Pending(i, j, executor.submit(ContextPropagation.wrap(() -> {
                    permits.acquire();
                    try { return cell(provider, profile, from, to); }
                    finally { permits.release(); }
                }))));
            }
            try {
                for (var cell : cells) result[cell.row()][cell.column()] = await(cell.cell());
            } catch (RuntimeException error) {
                // Only programming errors and interruption (the plan deadline) end the comparison early now.
                cells.forEach(cell -> cell.cell().cancel(true));
                throw error;
            }
        }
        return new RouteMatrix(result);
    }

    private static RouteMatrix.Cell cell(RouteProvider provider, String profile, Location from, Location to) {
        try {
            var leg = provider.directions(profile, from, to);
            return RouteMatrix.Cell.route(leg.durationSeconds(), leg.distanceMeters());
        } catch (RouteException error) {
            return error.code.equals("NO_ROAD_ROUTE") ? RouteMatrix.Cell.noRoute() : RouteMatrix.Cell.failed(error);
        } catch (IllegalArgumentException error) {
            throw error;
        } catch (RuntimeException error) {
            return RouteMatrix.Cell.failed(unavailable());
        }
    }

    private static RouteMatrix.Cell await(Future<RouteMatrix.Cell> future) {
        try { return future.get(); }
        catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw unavailable();
        } catch (ExecutionException error) {
            throw rethrow(error.getCause());
        }
    }

    private static Leg join(CompletableFuture<Leg> future) {
        try { return future.join(); }
        catch (CompletionException error) { throw rethrow(error.getCause()); }
    }

    private static RuntimeException rethrow(Throwable cause) {
        if (cause instanceof RouteException routeError) return routeError;
        if (cause instanceof IllegalArgumentException argumentError) return argumentError;
        return unavailable();
    }

    @Override public Leg directions(String profile, Location from, Location to) {
        boolean walk = switch (profile) {
            case WALKING -> true;
            case CYCLING, CYCLING_SHORTEST -> false;
            default -> throw new IllegalArgumentException("지원하지 않는 이동 방식입니다.");
        };
        // Kakao bicycle route_mode: BIKE_ONLY prefers bike roads (default, preferBikeRoad=true), SHORTEST does not.
        // [unverified against the live API] SHORTEST is taken from the phase 3 plan decision 5.
        String mode = walk ? "BROAD_FIRST" : profile.equals(CYCLING_SHORTEST) ? "SHORTEST" : "BIKE_ONLY";
        if (!configured()) throw new RouteException(HttpStatus.SERVICE_UNAVAILABLE, "ROUTING_NOT_CONFIGURED", "길찾기 연결을 준비하고 있어요.");
        if (from.distanceTo(to) < 0.5) return new Leg(walk ? "walk" : "bicycle", 0, 0, List.of(from, to), List.of());
        JsonNode body;
        try {
            // Only the HTTP exchange runs under the breaker/retry: "no road route" answers are successful exchanges.
            var response = (walk ? resilience.walking() : resilience.bicycle()).call(() -> client.get()
                    .uri(uri -> uri.path(walk ? "/v2/routing/walk" : "/v2/routing/bicycle")
                    .queryParam("start_x", from.longitude()).queryParam("start_y", from.latitude())
                    .queryParam("end_x", to.longitude()).queryParam("end_y", to.latitude())
                    .queryParam("input_coord", "WGS84").queryParam("output_coord", "WGS84")
                    .queryParam("route_mode", mode).build())
                    .header("Authorization", "KakaoAK " + key).retrieve().body(String.class), true);
            if (response == null || response.isBlank()) throw new IllegalStateException();
            body = mapper.readTree(response);
            if (body == null || !body.isObject()) throw new IllegalStateException();
        } catch (ProviderCallException error) {
            // Timeout -> 504 EXTERNAL_API_TIMEOUT; open breaker / full bulkhead -> immediate 502 via the adapters.
            if (error.kind() == ProviderCallException.Kind.TIMEOUT) throw timeout();
            throw unavailable();
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
    /** The public code name itself, so the exception handler answers 504 without a new mapping (D13). */
    static RouteException timeout() {
        return new RouteException(HttpStatus.GATEWAY_TIMEOUT, "EXTERNAL_API_TIMEOUT", "경로 응답이 늦어지고 있어요. 잠시 후 다시 시도해 주세요.");
    }
    private static RouteException unavailable() {
        return new RouteException(HttpStatus.BAD_GATEWAY, "ROUTING_UNAVAILABLE", "경로를 가져오지 못했어요. 잠시 후 다시 시도해 주세요.");
    }
}
