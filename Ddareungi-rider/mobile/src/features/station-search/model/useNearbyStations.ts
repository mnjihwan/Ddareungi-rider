import { skipToken, useQuery } from '@tanstack/react-query';
import type { Coordinates } from '../../../shared/types/coordinates';
import { clusterRequestBounds } from '../../../shared/rider-contract/mapClusters';
import type { MapBounds } from '../../../shared/rider-contract/types';
import { fetchNearbyStations, fetchStationClusters, fetchStationDetail, fetchViewportStations } from '../api/stationApi';
import { batchStationsQuery, STATION_POLL_MS, STATION_REFRESH_MS } from './stationQueries';

export { STATION_REFRESH_MS };

export function isSupportedViewport(bounds: MapBounds): boolean {
  const { south, north, west, east } = bounds;
  return south >= -90 && north <= 90 && west >= -180 && east <= 180
    && south < north && west < east && north - south <= 1 && east - west <= 1;
}

export function useNearbyStations(center: Coordinates, radius = 500, limit = 30, enabled = true) {
  return useQuery({
    queryKey: ['nearby-stations', center.latitude, center.longitude, radius, limit],
    queryFn: ({ signal }) => fetchNearbyStations(center, signal, radius, limit),
    enabled,
    staleTime: STATION_REFRESH_MS,
    refetchInterval: STATION_POLL_MS,
  });
}

export function useViewportStations(bounds: MapBounds | undefined, enabled = true) {
  const request = enabled && bounds && isSupportedViewport(bounds) ? bounds : undefined;
  return useQuery({
    queryKey: ['viewport-stations', request],
    queryFn: request ? ({ signal }) => fetchViewportStations(request, signal) : skipToken,
    enabled: Boolean(request),
    staleTime: STATION_REFRESH_MS,
    refetchInterval: STATION_POLL_MS,
  });
}

export function useStationClusters(bounds: MapBounds | undefined, level: number | undefined) {
  const request = level !== undefined && bounds && isSupportedViewport(bounds)
    ? clusterRequestBounds(bounds, level) : undefined;
  return useQuery({
    queryKey: ['station-clusters', level, request],
    queryFn: request && level !== undefined ? ({ signal }) => fetchStationClusters(request, level, signal) : skipToken,
    staleTime: STATION_REFRESH_MS,
    refetchInterval: STATION_POLL_MS,
  });
}

export function useStationDetail(id?: string) {
  return useQuery({
    queryKey: ['station-detail', id],
    queryFn: id ? ({ signal }) => fetchStationDetail(id, signal) : skipToken,
    enabled: Boolean(id),
    staleTime: STATION_REFRESH_MS,
    refetchInterval: STATION_POLL_MS,
  });
}

export function useBatchStations(ids: string[], center: Coordinates) {
  return useQuery(batchStationsQuery(ids, center));
}
