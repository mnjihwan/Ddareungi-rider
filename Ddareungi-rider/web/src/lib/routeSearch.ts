// Route search (spec 13.1, phase 3 A6): maps the `/routes/search` contract to the planner/map view model and turns
// failures into user-facing text. No axios/Vite imports, so node tests load it directly.
import { apiError, apiErrorMessage, isRateLimited, RATE_LIMIT_MESSAGE, type ApiMeta } from "./apiEnvelope.ts";
import { decodePolyline } from "./polyline.ts";
import type { BikeRoute, Coordinates, RoutePlan, RouteSearchResult, RouteSearchRoute } from "./types.ts";

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

/** `recommended` first, then `alternatives` in server order. Throws on a body that is not a search result. */
export function toRoutePlan(result: RouteSearchResult, meta: ApiMeta | undefined, origin: Coordinates,
  destination: Coordinates): RoutePlan {
  if (!result?.recommended || !Array.isArray(result.recommended.segments) || !Array.isArray(result.alternatives)) {
    throw new Error("Invalid route search response");
  }
  const attribution = typeof result.attribution === "string" ? result.attribution : "";
  return {
    routes: [result.recommended, ...result.alternatives].map(route => toBikeRoute(route, origin, destination, attribution)),
    partial: meta?.partial === true && (meta.warnings ?? []).some(warning => warning.code === "PROVIDER_PARTIAL_FAILURE"),
  };
}

export const ROUTE_FALLBACK_MESSAGE = "경로를 가져오지 못했어요. 잠시 후 다시 검색해 주세요.";
export const ROUTE_ERROR_MESSAGES: Record<string, string> = {
  NO_AVAILABLE_ORIGIN_STATION: "출발지 주변에 대여 가능한 대여소가 없어요. 출발지를 옮기거나 대여소를 직접 골라 주세요.",
  NO_AVAILABLE_RETURN_STATION: "목적지 주변에 반납할 대여소가 없어요. 목적지를 옮기거나 대여소를 직접 골라 주세요.",
  UNSUPPORTED_ROUTE_OPTION: "지금은 지원하지 않는 경로 옵션이에요. 기본 설정으로 다시 검색해 주세요.",
  ROUTE_NOT_FOUND: "걷는 거리 조건 안에서 이용할 수 있는 경로를 찾지 못했어요. 다른 장소나 대여소로 다시 검색해 주세요.",
  RENTAL_STOCK_CHANGED: "대여소 수량이 바뀌어 경로를 다시 확인해야 해요. 다시 검색해 주세요.",
  PAYLOAD_TOO_LARGE: "요청 내용이 너무 커요. 출발지와 목적지를 다시 선택해 주세요.",
};
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
 * the matching guidance; otherwise the fallback.
 */
export function routeErrorMessage(error: unknown): string {
  if (isRateLimited(error)) return RATE_LIMIT_MESSAGE;
  const code = apiError(error)?.code;
  if (code && Object.hasOwn(ROUTE_ERROR_MESSAGES, code)) return ROUTE_ERROR_MESSAGES[code];
  const status = (error as { response?: { status?: unknown } } | null)?.response?.status;
  if (typeof status === "number" && PROVIDER_MESSAGES[status]) return PROVIDER_MESSAGES[status];
  return apiErrorMessage(error, (typeof status === "number" && BARE_STATUS_MESSAGES[status]) || ROUTE_FALLBACK_MESSAGE);
}
