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
export interface Station {
  stationId: string;
  stationName: string;
  address: string;
  location: Coordinates;
  rackTotalCount: number;
  availableBikeCount: number | null;
  distanceMeters: number;
  updatedAt: string | null;
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
