// Route search (spec 13.1, phase 3 A6): maps the `/routes/search` contract to the planner/map view model and turns
// failures into user-facing text. No axios/Vite imports, so node tests load it directly.
import { apiError, apiErrorMessage, isRateLimited, RATE_LIMIT_MESSAGE, type ApiMeta } from "./apiEnvelope.ts";
import { decodePolyline } from "./polyline.ts";
import type { BikeRoute, Coordinates, RoutePlan, RouteSearchOptions, RouteSearchResult, RouteSearchRoute,
  RoutingStatus, Station } from "./types.ts";

/** Spec 13.1 upper bound of `options.maxWalkingDistanceMeters` (the server rejects 10001 with a 400). */
export const MAX_WALKING_DISTANCE_METERS = 10_000;

export interface RouteStationChoice { departureStationId?: string; arrivalStationId?: string }
export interface RouteSearchBody extends RouteStationChoice {
  origin: Coordinates;
  destination: Coordinates;
  options?: RouteSearchOptions;
}

/** Whether the user picked a departure and/or arrival station (an empty ID counts as not picked). */
export function hasChosenStation(stations: RouteStationChoice = {}): boolean {
  return Boolean(stations.departureStationId || stations.arrivalStationId);
}

/**
 * Options the planner sends without user input; undefined when the server defaults are enough (no `options` key).
 * - A chosen station may be farther than the server's default walking limit (1000m for both WALK segments together),
 *   which turned a valid choice into 404 ROUTE_NOT_FOUND (the deprecated /plan had no limit). Chosen stations lift
 *   only that limit, to the spec maximum; the station search radius and the rest keep the server defaults.
 * - `preferBikeRoad: false` only when `/routes/status` says explicitly that the engine cannot honour the preference
 *   (omitting it means true there, which such an engine answers with 422 UNSUPPORTED_ROUTE_OPTION). A missing field
 *   (older server) or true leaves it out.
 */
export function defaultRouteSearchOptions(stations: RouteStationChoice = {},
  status?: Pick<RoutingStatus, "bikeRoadPreferenceSupported"> | null): RouteSearchOptions | undefined {
  const options: RouteSearchOptions = {};
  if (hasChosenStation(stations)) options.maxWalkingDistanceMeters = MAX_WALKING_DISTANCE_METERS;
  if (status?.bikeRoadPreferenceSupported === false) options.preferBikeRoad = false;
  return Object.keys(options).length ? options : undefined;
}

/**
 * JSON body of `POST /routes/search`. Undefined/empty station IDs and undefined option values are left out (the
 * server rejects explicit nulls); `overrides` win over the defaults above.
 */
export function buildRouteSearchBody(origin: Coordinates, destination: Coordinates, stations: RouteStationChoice = {},
  status?: Pick<RoutingStatus, "bikeRoadPreferenceSupported"> | null, overrides?: RouteSearchOptions): RouteSearchBody {
  const body: RouteSearchBody = { origin, destination };
  if (stations.departureStationId) body.departureStationId = stations.departureStationId;
  if (stations.arrivalStationId) body.arrivalStationId = stations.arrivalStationId;
  const merged = Object.fromEntries(Object.entries({ ...defaultRouteSearchOptions(stations, status), ...overrides })
    .filter(([, value]) => value !== undefined && value !== null)) as RouteSearchOptions;
  if (Object.keys(merged).length) body.options = merged;
  return body;
}

export function toBikeRoute(route: RouteSearchRoute, origin: Coordinates, destination: Coordinates,
  attribution: string): BikeRoute {
  return {
    routeId: route.routeId,
    origin, destination,
    departureStation: route.originStation,
    arrivalStation: route.destinationStation,
    legs: route.segments.map(segment => ({
      mode: segment.type === "BIKE" ? "bicycle" : "walk",
      distanceMeters: segment.distanceMeters,
      durationSeconds: segment.durationSeconds,
      path: decodePolyline(segment.encodedPolyline),
      steps: Array.isArray(segment.steps) ? segment.steps : [],
      ...(segment.bikeRoadPreferred === undefined ? {} : { bikeRoadPreferred: segment.bikeRoadPreferred }),
    })),
    distanceMeters: route.totalDistanceMeters,
    durationSeconds: route.estimatedDurationSeconds,
    walkingDistanceMeters: route.walkingDistanceMeters,
    bikeDistanceMeters: route.bikeDistanceMeters,
    rentalAvailability: route.rentalAvailability,
    attribution,
  };
}

function isPartialPlan(meta: ApiMeta | undefined): boolean {
  return meta?.partial === true && (meta.warnings ?? []).some(warning => warning.code === "PROVIDER_PARTIAL_FAILURE");
}

function attributionOf(result: RouteSearchResult): string {
  return typeof result.attribution === "string" ? result.attribution : "";
}

/** `recommended` first, then `alternatives` in server order. Throws on a body that is not a search result. */
export function toRoutePlan(result: RouteSearchResult, meta: ApiMeta | undefined, origin: Coordinates,
  destination: Coordinates): RoutePlan {
  if (!result?.recommended || !Array.isArray(result.recommended.segments) || !Array.isArray(result.alternatives)) {
    throw new Error("Invalid route search response");
  }
  const attribution = attributionOf(result);
  return {
    routes: [result.recommended, ...result.alternatives].map(route => toBikeRoute(route, origin, destination, attribution)),
    partial: isPartialPlan(meta),
  };
}

/** Segment order every planner/map route is drawn with (spec 13.1): walk to the rental, ride, walk on. */
export const DRAWABLE_SEGMENT_TYPES = ["WALK", "BIKE", "WALK"] as const;

function isStation(value: unknown): value is Station {
  return typeof value === "object" && value !== null
    && typeof (value as Station).stationId === "string" && typeof (value as Station).stationName === "string";
}

/**
 * One route as the planner and map draw it, or null when it cannot be drawn: a segment order other than
 * WALK, BIKE, WALK, a missing rental/return station, a malformed polyline, or a leg with fewer than two points
 * (spec 13.1 guarantees at least two).
 */
export function toDrawableBikeRoute(route: RouteSearchRoute, origin: Coordinates, destination: Coordinates,
  attribution: string): BikeRoute | null {
  if (!route || typeof route !== "object" || !Array.isArray(route.segments)
    || !isStation(route.originStation) || !isStation(route.destinationStation)
    || route.segments.map(segment => segment?.type).join(",") !== DRAWABLE_SEGMENT_TYPES.join(",")) {
    return null;
  }
  try {
    const bikeRoute = toBikeRoute(route, origin, destination, attribution);
    return bikeRoute.legs.every(leg => leg.path.length >= 2) ? bikeRoute : null;
  } catch {
    return null; // decodePolyline rejects truncated or out-of-range input.
  }
}

/**
 * `toRoutePlan` for the planner and map: an undrawable recommended route rejects the response, and undrawable
 * alternatives are dropped so that one bad alternative cannot fail the whole search.
 */
export function toDrawableRoutePlan(result: RouteSearchResult, meta: ApiMeta | undefined, origin: Coordinates,
  destination: Coordinates): RoutePlan {
  if (!result?.recommended || !Array.isArray(result.alternatives)) throw new Error("Invalid route search response");
  const attribution = attributionOf(result);
  const recommended = toDrawableBikeRoute(result.recommended, origin, destination, attribution);
  if (!recommended) throw new Error("Invalid route legs");
  const alternatives = result.alternatives
    .map(route => toDrawableBikeRoute(route, origin, destination, attribution))
    .filter((route): route is BikeRoute => route !== null);
  return { routes: [recommended, ...alternatives], partial: isPartialPlan(meta) };
}

export const ROUTE_FALLBACK_MESSAGE = "경로를 가져오지 못했어요. 잠시 후 다시 검색해 주세요.";
export const ROUTE_ERROR_MESSAGES: Record<string, string> = {
  NO_AVAILABLE_ORIGIN_STATION: "출발지 주변에 대여 가능한 대여소가 없어요. 출발지를 옮기거나 대여소를 직접 골라 주세요.",
  NO_AVAILABLE_RETURN_STATION: "목적지 주변에 반납할 대여소가 없어요. 목적지를 옮기거나 대여소를 직접 골라 주세요.",
  UNSUPPORTED_ROUTE_OPTION: "지금은 지원하지 않는 경로 옵션이에요. 기본 설정으로 다시 검색해 주세요.",
  ROUTE_NOT_FOUND: "걸어서 갈 수 있는 거리 안에서 경로를 찾지 못했어요. 출발지·목적지를 대여소 가까이 옮기거나, 대여소를 직접 골라 다시 검색해 주세요.",
  RENTAL_STOCK_CHANGED: "대여소 수량이 바뀌어 경로를 다시 확인해야 해요. 다시 검색해 주세요.",
  PAYLOAD_TOO_LARGE: "요청 내용이 너무 커요. 출발지와 목적지를 다시 선택해 주세요.",
};
/** ROUTE_NOT_FOUND after the user picked a station (the walking limit was already lifted to the spec maximum). */
export const ROUTE_NOT_FOUND_CHOSEN_STATION_MESSAGE =
  "선택한 대여소까지 걷는 거리가 너무 멀어 경로를 찾지 못했어요. 출발지·목적지와 가까운 대여소를 골라 다시 검색해 주세요.";
/** Provider failures: every 502 code (WALK_/BIKE_ROUTE_PROVIDER_ERROR, …) and 504 EXTERNAL_API_TIMEOUT. */
const PROVIDER_MESSAGES: Record<number, string> = {
  502: "경로 서비스에서 응답을 받지 못했어요. 잠시 후 다시 검색해 주세요.",
  504: "경로 서비스 응답이 늦어지고 있어요. 잠시 후 다시 검색해 주세요.",
};
/** Used only when the response has no failure envelope (e.g. a proxy answered). */
const BARE_STATUS_MESSAGES: Record<number, string> = {
  404: ROUTE_ERROR_MESSAGES.ROUTE_NOT_FOUND,
  409: ROUTE_ERROR_MESSAGES.RENTAL_STOCK_CHANGED,
  413: ROUTE_ERROR_MESSAGES.PAYLOAD_TOO_LARGE,
};

/**
 * Order: 429 → the shared rate-limit text; a known route code → fixed guidance (what to change, not only what
 * failed); 502/504 → provider text; any other envelope (400 validation, other 422 codes such as
 * OUT_OF_SERVICE_AREA, 404 RESOURCE_NOT_FOUND) → the server's `error.message` as before; a bare 404/409/413 →
 * the matching guidance; otherwise the fallback. With `stationChosen`, ROUTE_NOT_FOUND points at the chosen
 * station instead of suggesting to choose one.
 */
export function routeErrorMessage(error: unknown, context: { stationChosen?: boolean } = {}): string {
  if (isRateLimited(error)) return RATE_LIMIT_MESSAGE;
  const code = apiError(error)?.code;
  if (code === "ROUTE_NOT_FOUND" && context.stationChosen) return ROUTE_NOT_FOUND_CHOSEN_STATION_MESSAGE;
  if (code && Object.hasOwn(ROUTE_ERROR_MESSAGES, code)) return ROUTE_ERROR_MESSAGES[code];
  const status = (error as { response?: { status?: unknown } } | null)?.response?.status;
  if (typeof status === "number" && PROVIDER_MESSAGES[status]) return PROVIDER_MESSAGES[status];
  return apiErrorMessage(error, (typeof status === "number" && BARE_STATUS_MESSAGES[status]) || ROUTE_FALLBACK_MESSAGE);
}
