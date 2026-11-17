package com.ddareungi.routing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code routing.search.*} (phase 3 decision 3).
 *
 * @param maxStationCandidates automatic candidates per side. Kakao has no matrix API, so one search costs
 *                             {@code 2n} walking + {@code n²} cycling calls; 3 keeps the budget at 6 + 9 = 15 calls,
 *                             so larger values are rejected at startup.
 */
@ConfigurationProperties("routing.search")
public record RouteSearchProperties(@DefaultValue("3") int maxStationCandidates) {
    public static final int MAX_STATION_CANDIDATES = 3;

    public RouteSearchProperties {
        if (maxStationCandidates < 1 || maxStationCandidates > MAX_STATION_CANDIDATES)
            throw new IllegalArgumentException("routing.search.max-station-candidates must be 1.."
                    + MAX_STATION_CANDIDATES + " (Kakao call budget of 15 per search)");
    }

    public static RouteSearchProperties defaults() { return new RouteSearchProperties(MAX_STATION_CANDIDATES); }
}
