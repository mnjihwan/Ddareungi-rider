import test from "node:test";
import assert from "node:assert/strict";
import { api, searchBikeRoutes } from "../src/lib/api.ts";
import { routeErrorMessage, toRoutePlan, buildRouteSearchBody, defaultRouteSearchOptions, hasChosenStation,
  ROUTE_ERROR_MESSAGES, ROUTE_FALLBACK_MESSAGE, ROUTE_NOT_FOUND_CHOSEN_STATION_MESSAGE, MAX_WALKING_DISTANCE_METERS,
} from "../src/lib/routeSearch.ts";
import { RATE_LIMIT_MESSAGE } from "../src/lib/apiEnvelope.ts";

const origin = { latitude: 37.4979, longitude: 127.0276 }, destination = { latitude: 37.5047, longitude: 127.0489 };
const station = (stationId, parkingBikeTotCnt = 5) => ({ stationId, stationName: `${stationId} 대여소`,
  stationLatitude: 37.5, stationLongitude: 127.03, rackTotCnt: 10, parkingBikeTotCnt, shared: 50, realtimeStatus: "FRESH",
  realtimeUpdatedAt: "2026-11-17T00:00:00Z", realtimeAgeSeconds: 5, distanceMeters: 120, address: null });
const GOOGLE = "_p~iF~ps|U_ulLnnqC_mqNvxq`@";
const route = (id, overrides = {}) => ({
  routeId: `route-${id}`, totalDistanceMeters: 2300, estimatedDurationSeconds: 760, walkingDistanceMeters: 300,
  bikeDistanceMeters: 2000, originStation: station(`${id}-A`), destinationStation: station(`${id}-B`),
  segments: [
    { type: "WALK", distanceMeters: 120, durationSeconds: 100, encodedPolyline: GOOGLE, steps: [] },
    { type: "BIKE", distanceMeters: 2000, durationSeconds: 540, bikeRoadPreferred: true, encodedPolyline: GOOGLE,
      steps: [{ instruction: "우회전", distanceMeters: 300, durationSeconds: 60 }] },
    { type: "WALK", distanceMeters: 180, durationSeconds: 120, encodedPolyline: "??", steps: [] },
  ],
  rentalAvailability: "AVAILABLE_AT_FETCH", returnAvailability: "UNKNOWN", ...overrides,
});
const result = { recommended: route("r"), alternatives: [route("a1", { rentalAvailability: "UNKNOWN" }), route("a2")],
  attribution: "경로: Kakao Mobility" };

test("search response maps to the planner/map view: recommended first, decoded paths, request endpoints", () => {
  const plan = toRoutePlan(result, { requestId: "r" }, origin, destination);
  assert.equal(plan.partial, false);
  assert.deepEqual(plan.routes.map(r => r.routeId), ["route-r", "route-a1", "route-a2"]);
  const [first, second] = plan.routes;
  assert.deepEqual(first.origin, origin);
  assert.deepEqual(first.destination, destination);
  assert.equal(first.departureStation.stationId, "r-A");
  assert.equal(first.arrivalStation.stationId, "r-B");
  assert.deepEqual(first.legs.map(leg => leg.mode), ["walk", "bicycle", "walk"]);
  assert.deepEqual(first.legs[0].path[2], { latitude: 43.252, longitude: -126.453 });
  assert.deepEqual(first.legs[2].path, [{ latitude: 0, longitude: 0 }]);
  assert.equal(first.legs[1].bikeRoadPreferred, true);
  assert.equal("bikeRoadPreferred" in first.legs[0], false);
  assert.deepEqual(first.legs[1].steps, [{ instruction: "우회전", distanceMeters: 300, durationSeconds: 60 }]);
  assert.equal(first.distanceMeters, 2300);
  assert.equal(first.durationSeconds, 760);
  assert.equal(first.walkingDistanceMeters, 300);
  assert.equal(first.bikeDistanceMeters, 2000);
  assert.equal(first.attribution, "경로: Kakao Mobility");
  assert.equal(second.rentalAvailability, "UNKNOWN");
});

test("partial flag only for PROVIDER_PARTIAL_FAILURE; a body without recommended/alternatives is rejected", () => {
  const partial = { requestId: "r", partial: true, warnings: [{ code: "PROVIDER_PARTIAL_FAILURE" }] };
  assert.equal(toRoutePlan(result, partial, origin, destination).partial, true);
  assert.equal(toRoutePlan(result, { requestId: "r", partial: true, warnings: [{ code: "STALE_DATA" }] }, origin, destination).partial, false);
  assert.equal(toRoutePlan({ ...result, alternatives: [] }, undefined, origin, destination).routes.length, 1);
  // The deprecated /routes/plan shape must not be accepted silently.
  assert.throws(() => toRoutePlan({ legs: [], departureStation: station("x") }, undefined, origin, destination));
  assert.throws(() => toRoutePlan({ ...result, recommended: { ...route("r"), segments: [{ type: "WALK",
    distanceMeters: 1, durationSeconds: 1, encodedPolyline: "_", steps: [] }] } }, undefined, origin, destination));
});

test("searchBikeRoutes posts to /routes/search with station IDs only when chosen and no null options", async (t) => {
  const calls = [];
  const original = api.post;
  api.post = async (url, body, config) => { calls.push({ url, body: JSON.parse(JSON.stringify(body)), config });
    return { data: { success: true, data: result, meta: { requestId: "r" }, timestamp: "t" } }; };
  t.after(() => { api.post = original; });
  const plan = await searchBikeRoutes(origin, destination, { departureStationId: "ST-1", arrivalStationId: undefined });
  assert.equal(calls[0].url, "/routes/search");
  // A chosen station lifts only the walking limit (M1); no arrivalStationId key, no null.
  assert.deepEqual(calls[0].body, { origin, destination, departureStationId: "ST-1",
    options: { maxWalkingDistanceMeters: 10_000 } });
  assert.equal(calls[0].config.timeout, 90_000);
  assert.equal(plan.routes.length, 3);
  await searchBikeRoutes(origin, destination, {}, undefined, { maxCandidates: 3, preferBikeRoad: false });
  assert.deepEqual(calls[1].body, { origin, destination, options: { maxCandidates: 3, preferBikeRoad: false } });
  // A plain search keeps every server default: no options key at all.
  await searchBikeRoutes(origin, destination);
  assert.deepEqual(calls[2].body, { origin, destination });
  // L3: the routing status decides preferBikeRoad.
  await searchBikeRoutes(origin, destination, {}, undefined, undefined, { bikeRoadPreferenceSupported: false });
  assert.deepEqual(calls[3].body, { origin, destination, options: { preferBikeRoad: false } });
});

test("request body: chosen stations lift only the walking limit to the spec maximum; plain search sends no options", () => {
  assert.equal(MAX_WALKING_DISTANCE_METERS, 10_000);
  const lifted = { maxWalkingDistanceMeters: 10_000 };
  // Both chosen.
  assert.deepEqual(buildRouteSearchBody(origin, destination, { departureStationId: "D", arrivalStationId: "A" }),
    { origin, destination, departureStationId: "D", arrivalStationId: "A", options: lifted });
  // Only one of them.
  assert.deepEqual(buildRouteSearchBody(origin, destination, { departureStationId: "D" }),
    { origin, destination, departureStationId: "D", options: lifted });
  assert.deepEqual(buildRouteSearchBody(origin, destination, { departureStationId: undefined, arrivalStationId: "A" }),
    { origin, destination, arrivalStationId: "A", options: lifted });
  // None chosen (absent, undefined or empty IDs): server defaults, no options and no station keys.
  for (const stations of [undefined, {}, { departureStationId: undefined, arrivalStationId: undefined },
    { departureStationId: "", arrivalStationId: "" }]) {
    const body = buildRouteSearchBody(origin, destination, stations);
    assert.deepEqual(body, { origin, destination });
    assert.equal("options" in body, false);
    assert.equal(defaultRouteSearchOptions(stations), undefined);
    assert.equal(hasChosenStation(stations), false);
  }
  // Other options are never sent by default (stationSearchRadiusMeters etc. stay server defaults).
  const options = buildRouteSearchBody(origin, destination, { arrivalStationId: "A" }).options;
  assert.deepEqual(Object.keys(options), ["maxWalkingDistanceMeters"]);
  // The serialized JSON has no null anywhere.
  assert.equal(JSON.stringify(buildRouteSearchBody(origin, destination, { departureStationId: "D", arrivalStationId: undefined },
    null, { maxCandidates: undefined, preferBikeRoad: null })).includes("null"), false);
  // Explicit options win over the defaults and are merged with them.
  assert.deepEqual(buildRouteSearchBody(origin, destination, { departureStationId: "D" }, null,
    { maxWalkingDistanceMeters: 2000, maxCandidates: 3 }).options, { maxWalkingDistanceMeters: 2000, maxCandidates: 3 });
});

test("request body: preferBikeRoad=false only when the status says the engine does not support it (L3)", () => {
  assert.deepEqual(buildRouteSearchBody(origin, destination, {}, { bikeRoadPreferenceSupported: false }),
    { origin, destination, options: { preferBikeRoad: false } });
  for (const status of [undefined, null, {}, { configured: true }, { bikeRoadPreferenceSupported: true }]) {
    assert.deepEqual(buildRouteSearchBody(origin, destination, {}, status), { origin, destination });
  }
  assert.deepEqual(buildRouteSearchBody(origin, destination, { departureStationId: "D" },
    { configured: true, bikeRoadPreferenceSupported: false }).options,
    { maxWalkingDistanceMeters: 10_000, preferBikeRoad: false });
  assert.deepEqual(buildRouteSearchBody(origin, destination, { arrivalStationId: "A" },
    { bikeRoadPreferenceSupported: true }).options, { maxWalkingDistanceMeters: 10_000 });
});

const failure = (status, code, message = "서버 메시지") => ({ isAxiosError: true, response: { status, headers: {},
  data: code ? { success: false, error: { code, message, details: [] }, meta: { requestId: "r" }, timestamp: "t" } : "" } });

test("route error codes and statuses map to guidance (422/404/409/413/502/504/429)", () => {
  assert.equal(routeErrorMessage(failure(422, "NO_AVAILABLE_ORIGIN_STATION")), ROUTE_ERROR_MESSAGES.NO_AVAILABLE_ORIGIN_STATION);
  assert.equal(routeErrorMessage(failure(422, "NO_AVAILABLE_RETURN_STATION")), ROUTE_ERROR_MESSAGES.NO_AVAILABLE_RETURN_STATION);
  assert.equal(routeErrorMessage(failure(422, "UNSUPPORTED_ROUTE_OPTION")), ROUTE_ERROR_MESSAGES.UNSUPPORTED_ROUTE_OPTION);
  assert.equal(routeErrorMessage(failure(404, "ROUTE_NOT_FOUND")), ROUTE_ERROR_MESSAGES.ROUTE_NOT_FOUND);
  // Plain search (default walking limit): suggest moving closer to a station or choosing one.
  assert.match(ROUTE_ERROR_MESSAGES.ROUTE_NOT_FOUND, /대여소 가까이/);
  assert.match(ROUTE_ERROR_MESSAGES.ROUTE_NOT_FOUND, /대여소를 직접 골라/);
  assert.equal(routeErrorMessage(failure(404, "ROUTE_NOT_FOUND"), { stationChosen: false }), ROUTE_ERROR_MESSAGES.ROUTE_NOT_FOUND);
  // A chosen station already lifted the limit: point at that station instead.
  assert.equal(routeErrorMessage(failure(404, "ROUTE_NOT_FOUND"), { stationChosen: true }), ROUTE_NOT_FOUND_CHOSEN_STATION_MESSAGE);
  assert.equal(routeErrorMessage(failure(422, "NO_AVAILABLE_ORIGIN_STATION"), { stationChosen: true }),
    ROUTE_ERROR_MESSAGES.NO_AVAILABLE_ORIGIN_STATION);
  assert.equal(routeErrorMessage(failure(429, "RATE_LIMIT_EXCEEDED"), { stationChosen: true }), RATE_LIMIT_MESSAGE);
  assert.equal(routeErrorMessage(failure(409, "RENTAL_STOCK_CHANGED")), ROUTE_ERROR_MESSAGES.RENTAL_STOCK_CHANGED);
  assert.equal(routeErrorMessage(failure(413, "PAYLOAD_TOO_LARGE")), ROUTE_ERROR_MESSAGES.PAYLOAD_TOO_LARGE);
  assert.match(routeErrorMessage(failure(502, "WALK_ROUTE_PROVIDER_ERROR")), /응답을 받지 못했어요/);
  assert.match(routeErrorMessage(failure(502, "BIKE_ROUTE_PROVIDER_ERROR")), /응답을 받지 못했어요/);
  assert.match(routeErrorMessage(failure(504, "EXTERNAL_API_TIMEOUT")), /늦어지고/);
  assert.match(routeErrorMessage(failure(504)), /늦어지고/);   // gateway timeout from a proxy, no envelope
  assert.equal(routeErrorMessage(failure(429, "RATE_LIMIT_EXCEEDED")), RATE_LIMIT_MESSAGE);
  assert.equal(routeErrorMessage(failure(429)), RATE_LIMIT_MESSAGE);
  // Bare statuses (no envelope, e.g. nginx 413) still get guidance.
  assert.equal(routeErrorMessage(failure(413)), ROUTE_ERROR_MESSAGES.PAYLOAD_TOO_LARGE);
  assert.equal(routeErrorMessage(failure(409)), ROUTE_ERROR_MESSAGES.RENTAL_STOCK_CHANGED);
});

test("other envelopes keep the server message (as before A6); unknown failures use the fallback", () => {
  assert.equal(routeErrorMessage(failure(422, "OUT_OF_SERVICE_AREA", "서비스 대상 지역이 아닙니다.")), "서비스 대상 지역이 아닙니다.");
  assert.equal(routeErrorMessage(failure(400, "INVALID_PARAMETER", "요청 값이 올바르지 않습니다.")), "요청 값이 올바르지 않습니다.");
  assert.equal(routeErrorMessage(failure(404, "RESOURCE_NOT_FOUND", "요청한 경로를 찾지 못했습니다.")), "요청한 경로를 찾지 못했습니다.");
  assert.equal(routeErrorMessage(failure(500)), ROUTE_FALLBACK_MESSAGE);
  assert.equal(routeErrorMessage(new Error("Network Error")), ROUTE_FALLBACK_MESSAGE);
  assert.equal(routeErrorMessage(failure(422, "toString")), "서버 메시지");   // prototype keys are not codes
});
