export type RouteEndpointRole = "origin" | "destination";

export interface Coordinates {
  latitude: number;
  longitude: number;
}

export interface RouteLeg {
  mode: 'walk' | 'bicycle';
  distanceMeters: number;
  durationSeconds: number;
  path: Coordinates[];
  steps: {instruction: string; distanceMeters: number; durationSeconds: number}[];
}
export interface BikeRoute {
  origin: Coordinates;
  destination: Coordinates;
  departureStation: Station;
  arrivalStation: Station;
  legs: RouteLeg[];
  distanceMeters: number;
  durationSeconds: number;
  generatedAt: string;
  attribution: string;
}
export interface MapBounds {
  south: number;
  north: number;
  west: number;
  east: number;
}
/** Public station object (API spec 11.0, source field names since phase 1 / X1). */
export interface Station {
  stationId: string;
  stationName: string;
  stationLatitude: number;
  stationLongitude: number;
  /** Rack count; null when the source omits it. Not a return-availability count. */
  rackTotCnt: number | null;
  /** Rentable bikes at the last successful collection; null while unknown (phase 1). */
  parkingBikeTotCnt: number | null;
  /** Source occupancy ratio (%); always null until A3. */
  shared: number | null;
  /** When the realtime values were collected (not when bikes changed). */
  realtimeUpdatedAt: string | null;
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
