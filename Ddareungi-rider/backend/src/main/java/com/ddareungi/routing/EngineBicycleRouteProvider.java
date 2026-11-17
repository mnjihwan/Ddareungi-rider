package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Supplier;

/**
 * Bicycle provider on the engine selected by {@code routing.provider}. The default profile is cycling-regular (Kakao
 * {@code BIKE_ONLY}); {@code preferBikeRoad=false} on an engine that supports it uses {@link RouteProvider#CYCLING_SHORTEST}
 * (Kakao {@code SHORTEST}). The profile is part of the engine's per-request cache key, so the modes never mix.
 */
@Component
public class EngineBicycleRouteProvider implements BicycleRouteProvider {
    static final String PROFILE = RouteProvider.CYCLING;
    static final String PROVIDER_ERROR = "BIKE_ROUTE_PROVIDER_ERROR";
    private final RouteProvider engine;
    private final String profile;

    @Autowired
    public EngineBicycleRouteProvider(RouteProvider engine) { this(engine, PROFILE); }
    private EngineBicycleRouteProvider(RouteProvider engine, String profile) { this.engine = engine; this.profile = profile; }

    @Override public boolean configured() { return engine.configured(); }
    @Override public String attribution() { return engine.attribution(); }
    @Override public boolean supportsBikeRoadPreference() { return engine.supportsBikeRoadPreference(); }
    @Override public BicycleRouteProvider forRequest() { return forRequest(null); }
    @Override public BicycleRouteProvider forRequest(@Nullable Boolean preferBikeRoad) {
        String mode = Boolean.FALSE.equals(preferBikeRoad) && engine.supportsBikeRoadPreference()
                ? RouteProvider.CYCLING_SHORTEST : PROFILE;
        return new EngineBicycleRouteProvider(translate(engine::forRequest), mode);
    }
    @Override public Double[][] durations(List<Location> sources, List<Location> destinations) {
        return translate(() -> engine.durations(profile, sources, destinations));
    }
    @Override public RouteMatrix matrix(List<Location> sources, List<Location> destinations) {
        return translate(() -> engine.matrix(profile, sources, destinations)).mapFailures(EngineBicycleRouteProvider::translated);
    }
    @Override public RouteProvider.Leg route(Location from, Location to) {
        return translate(() -> engine.directions(profile, from, to));
    }

    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (RouteException error) {
            // NO_ROAD_ROUTE, ROUTING_NOT_CONFIGURED and other codes keep their meaning.
            throw translated(error);
        }
    }

    private static RouteException translated(RouteException error) {
        if (!RouteProvider.PROVIDER_FAILURE_CODES.contains(error.code)) return error;
        // Engine messages are already user-safe (never provider bodies or keys); keep the engine error as cause for logs.
        var translated = new RouteException(HttpStatus.BAD_GATEWAY, PROVIDER_ERROR, error.getMessage());
        translated.initCause(error);
        return translated;
    }
}
