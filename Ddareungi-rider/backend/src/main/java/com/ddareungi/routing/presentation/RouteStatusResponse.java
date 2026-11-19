package com.ddareungi.routing.presentation;

/**
 * {@code GET /api/v1/routes/status}: whether route planning is enabled and its providers are configured.
 *
 * @param bikeRoadPreferenceSupported review L3: whether the current engine honours {@code options.preferBikeRoad}
 *                                    both ways (Kakao: true). When false, clients must send {@code preferBikeRoad=false}
 *                                    (or leave it out of the legacy plan), since {@code true} is refused with 422
 *                                    {@code UNSUPPORTED_ROUTE_OPTION}. Independent of {@code configured}.
 */
public record RouteStatusResponse(boolean configured, boolean bikeRoadPreferenceSupported) {}
