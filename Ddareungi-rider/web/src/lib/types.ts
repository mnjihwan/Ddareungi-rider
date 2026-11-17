export type RouteEndpointRole = "origin" | "destination";

export interface Coordinates {
  latitude: number;
  longitude: number;
}

// ---- POST /routes/search (spec 13.1, phase 3 A6) — wire format ----

export interface RouteStep { instruction: string; distanceMeters: number; durationSeconds: number }
export type RouteSegmentType = "WALK" | "BIKE";
export type RentalAvailability = "AVAILABLE_AT_FETCH" | "UNAVAILABLE_AT_FETCH" | "UNKNOWN";
export interface RouteSegment {
  type: RouteSegmentType;
  /** Rounded to whole metres/seconds by the server; route totals are sums of these. */
  distanceMeters: number;
  durationSeconds: number;
  /** BIKE only: whether the bike-road preference was applied (not a bike-road ratio). */
  bikeRoadPreferred?: boolean;
  /** Google Encoded Polyline, precision 1e5 (see `polyline.ts`). Always at least two points. */
  encodedPolyline: string;
  /** Extension field: provider turn-by-turn guidance. */
  steps: RouteStep[];
}
export interface RouteSearchRoute {
  routeId: string;
  totalDistanceMeters: number;
  /** Lock/unlock and waiting time are not included. */
  estimatedDurationSeconds: number;
  walkingDistanceMeters: number;
  bikeDistanceMeters: number;
  originStation: Station;
  destinationStation: Station;
  segments: RouteSegment[];
  rentalAvailability: RentalAvailability;
  returnAvailability: "UNKNOWN";
}
export interface RouteSearchResult {
  recommended: RouteSearchRoute;
  alternatives: RouteSearchRoute[];
  /** Extension field. */
  attribution: string;
}
/** Request options; omitted values take the server defaults (an explicit null is a 400). */
export interface RouteSearchOptions {
  stationSearchRadiusMeters?: number;
  maxWalkingDistanceMeters?: number;
  preferBikeRoad?: boolean;
  excludeStationsWithoutBikes?: boolean;
  maxCandidates?: number;
}

// ---- View model used by the planner and the map (polylines decoded, request endpoints kept) ----

export interface RouteLeg {
  mode: 'walk' | 'bicycle';
  distanceMeters: number;
  durationSeconds: number;
  path: Coordinates[];
  steps: RouteStep[];
  bikeRoadPreferred?: boolean;
}
export interface BikeRoute {
  routeId: string;
  /** The searched endpoints (the search response no longer echoes them). */
  origin: Coordinates;
  destination: Coordinates;
  departureStation: Station;
  arrivalStation: Station;
  legs: RouteLeg[];
  distanceMeters: number;
  durationSeconds: number;
  walkingDistanceMeters: number;
  bikeDistanceMeters: number;
  rentalAvailability: RentalAvailability;
  attribution: string;
}
/** A search result: `routes[0]` is the recommended route, the rest are alternatives in server order. */
export interface RoutePlan {
  routes: BikeRoute[];
  /** `meta.partial` with PROVIDER_PARTIAL_FAILURE: some candidate pairs could not be evaluated. */
  partial: boolean;
}
export interface MapBounds {
  south: number;
  north: number;
  west: number;
  east: number;
}
export type RealtimeStatus = "FRESH" | "STALE" | "UNAVAILABLE";

/** Public station object (API spec 11.0, source field names since phase 1 / X1). */
export interface Station {
  stationId: string;
  stationName: string;
  stationLatitude: number;
  stationLongitude: number;
  /** Rack count; null when the source omits it. Not a return-availability count. */
  rackTotCnt: number | null;
  /** Rentable bikes at the last successful collection. Station lists only contain FRESH/STALE items, where it is set. */
  parkingBikeTotCnt: number | null;
  /** Source occupancy ratio (%); null when the source omits it. Values above 100 are passed through. */
  shared: number | null;
  /**
   * Server-side freshness at response time (spec 11.0): FRESH (age <= 120s), STALE (<= 600s, counts are the
   * last known values). UNAVAILABLE is internal and only appears outside station lists (e.g. a route's station).
   * Optional for values cached before A3.
   */
  realtimeStatus?: RealtimeStatus;
  /** When the realtime values were collected (not when bikes changed). */
  realtimeUpdatedAt: string | null;
  /** Whole seconds between collection and response time (floor, min 0); null when unknown. */
  realtimeAgeSeconds?: number | null;
  distanceMeters: number;
  /** Extension field (not in spec 11.0); may be null. */
  address: string | null;
}

/** Station coordinates as the shared Coordinates shape used by maps, routing and storage. */
export function stationLocation(station: Pick<Station, "stationLatitude" | "stationLongitude">): Coordinates {
  return { latitude: station.stationLatitude, longitude: station.stationLongitude };
}
export interface Place {
  name: string;
  address: string;
  location: Coordinates;
  sourceUrl?: string;
  categoryGroupCode?: string;
}
export interface Forecast {
  stationId: string;
  baseTime: string;
  generatedAt: string;
  modelVersion: string;
  dataStatus: string;
  predictions: { targetTime: string; expectedBikes: number }[];
}
export const INITIAL_LOCATION: Coordinates = {
  latitude: 37.4979,
  longitude: 127.0276,
};

/** `GET /stations/clusters` data (phase 3 R4, extension API, map levels 5..8). */
export interface StationClusterItem {
  /** Mean position of the cell's FRESH/STALE stations (not the geometric cell centre). */
  latitude: number;
  longitude: number;
  stationCount: number;
  /** Sum over FRESH and STALE stations (STALE counts are last known values). */
  parkingBikeTotCnt: number;
  freshCount: number;
}
export interface StationClusters {
  level: number;
  cellDegrees: number;
  items: StationClusterItem[];
}
/** `GET /stations/viewport` result: `truncated` when the server cut it at its cap (meta.partial + RESULT_TRUNCATED). */
export interface ViewportStations {
  stations: Station[];
  truncated: boolean;
}

/** `GET /stations/nearby` data (spec 11.1, A5). `api.nearby()` returns only `items`. */
export interface NearbyStations {
  center: Coordinates;
  radiusMeters: number;
  items: Station[];
}
