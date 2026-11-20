import type { Station } from '../../../entities/station/model/types';
import { apiClient, unwrapApiData, type ApiEnvelope } from '../../../shared/api/client';
import { INITIAL_LOCATION, type Coordinates } from '../../../shared/types/coordinates';
import type { MapRouteSegment } from '../model/scenes';

interface LegacyRoutePlan {
  departureStation: Station | null;
  arrivalStation: Station | null;
  legs: { mode: string; path: Coordinates[] }[];
  distanceMeters: number;
  durationSeconds: number;
}

export interface RouteMappingCheck {
  departureStation: Station;
  arrivalStation: Station;
  segments: MapRouteSegment[];
  distanceMeters: number;
  durationSeconds: number;
  receivedAt: number;
}

// Device smoke check for the deprecated raw-path contract. The rider flow uses routes/search.
export async function requestRouteMappingCheck(): Promise<RouteMappingCheck> {
  const response = await apiClient.post<ApiEnvelope<LegacyRoutePlan>>('/api/v1/routes/plan', {
    origin: INITIAL_LOCATION,
    destination: { latitude: 37.5006, longitude: 127.0364 },
  });
  const { departureStation, arrivalStation, legs, distanceMeters, durationSeconds } = unwrapApiData(response.data);
  const expectedModes = ['walk', 'bicycle', 'walk'];
  if (
    !departureStation || !arrivalStation || legs.length !== expectedModes.length
    || legs.some(({ mode, path }, index) => mode !== expectedModes[index] || path.length < 2)
  ) {
    throw new Error('대여·반납 대여소와 도보·자전거·도보 경로를 확인할 수 없습니다.');
  }
  return {
    departureStation,
    arrivalStation,
    distanceMeters,
    durationSeconds,
    receivedAt: Date.now(),
    segments: legs.map(({ path }, index) => ({ type: index === 1 ? 'BIKE' : 'WALK', path })),
  };
}
