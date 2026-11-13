package com.ddareungi.routing;

import com.ddareungi.station.domain.Location;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.function.Supplier;

/** Walking provider on the engine selected by {@code routing.provider}, fixed to the foot-walking profile. */
@Component
public class EngineWalkingRouteProvider implements WalkingRouteProvider {
    static final String PROFILE = "foot-walking";
    static final String PROVIDER_ERROR = "WALK_ROUTE_PROVIDER_ERROR";
    private final RouteProvider engine;

    public EngineWalkingRouteProvider(RouteProvider engine) { this.engine = engine; }

    @Override public boolean configured() { return engine.configured(); }
    @Override public String attribution() { return engine.attribution(); }
    @Override public WalkingRouteProvider forRequest() {
        return new EngineWalkingRouteProvider(translate(engine::forRequest));
    }
    @Override public Double[][] durations(List<Location> sources, List<Location> destinations) {
        return translate(() -> engine.durations(PROFILE, sources, destinations));
    }
    @Override public RouteProvider.Leg route(Location from, Location to) {
        return translate(() -> engine.directions(PROFILE, from, to));
    }

    private static <T> T translate(Supplier<T> call) {
        try {
            return call.get();
        } catch (RouteException error) {
            // NO_ROAD_ROUTE, ROUTING_NOT_CONFIGURED and other codes keep their meaning.
            if (!RouteProvider.PROVIDER_FAILURE_CODES.contains(error.code)) throw error;
            // Engine messages are already user-safe (never provider bodies or keys); keep the engine error as cause for logs.
            var translated = new RouteException(HttpStatus.BAD_GATEWAY, PROVIDER_ERROR, error.getMessage());
            translated.initCause(error);
            throw translated;
        }
    }
}
