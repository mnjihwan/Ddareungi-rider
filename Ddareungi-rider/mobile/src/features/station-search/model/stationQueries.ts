import { queryOptions } from '@tanstack/react-query';
import type { Coordinates } from '../../../shared/rider-contract/types.ts';
import { fetchBatchStations } from '../api/stationApi.ts';

export const STATION_REFRESH_MS = 30_000;
export const STATION_POLL_MS = 60_000;

export function sameStationIds(previous: unknown, ids: readonly string[]): boolean {
  return Array.isArray(previous) && previous.length === ids.length && previous.every((id, index) => id === ids[index]);
}

/**
 * Favorites and route stations by ID. The center only changes `distanceMeters`, so moving the map keeps the same
 * stations on screen while distances refresh. A different ID list starts empty.
 */
export function batchStationsQuery(ids: string[], center: Coordinates) {
  return queryOptions({
    queryKey: ['station-batch', ids, center] as const,
    queryFn: ({ signal }) => fetchBatchStations(ids, center, signal),
    enabled: ids.length > 0,
    staleTime: STATION_REFRESH_MS,
    refetchInterval: STATION_POLL_MS,
    placeholderData: (previous, previousQuery) => sameStationIds(previousQuery?.queryKey[1], ids) ? previous : undefined,
  });
}
