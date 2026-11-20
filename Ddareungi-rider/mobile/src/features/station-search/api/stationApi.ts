import type { Station } from '../../../entities/station/model/types';
import { apiClient, unwrapApiData, type ApiEnvelope } from '../../../shared/api/client.ts';
import type { InventoryRecord } from '../../../shared/rider-contract/inventoryComparison.ts';
import type { MapBounds, Place, StationClusters } from '../../../shared/rider-contract/types';
import type { Coordinates } from '../../../shared/types/coordinates';

export async function fetchNearbyStations(center: Coordinates, signal?: AbortSignal, radius = 500, limit = 30): Promise<Station[]> {
  const response = await apiClient.get<ApiEnvelope<{ items: Station[] }>>('/api/v1/stations/nearby', {
    params: { ...center, radius, limit },
    signal,
  });
  const data = unwrapApiData(response.data);
  if (!Array.isArray(data?.items)) throw new Error('Invalid nearby stations response');
  return data.items;
}

export async function fetchViewportStations(bounds: MapBounds, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Station[]>>('/api/v1/stations/viewport', { params: bounds, signal });
  const stations = unwrapApiData(data);
  if (!Array.isArray(stations)) throw new Error('Invalid viewport stations response');
  return {
    stations,
    truncated: data.meta?.partial === true && (data.meta.warnings ?? []).some((warning) => warning.code === 'RESULT_TRUNCATED'),
  };
}

export async function fetchStationClusters(bounds: MapBounds, level: number, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<StationClusters>>('/api/v1/stations/clusters', {
    params: { ...bounds, level }, signal,
  });
  const clusters = unwrapApiData(data);
  if (!Array.isArray(clusters?.items)) throw new Error('Invalid station clusters response');
  return clusters.items;
}

export async function fetchStationDetail(id: string, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Omit<Station, 'distanceMeters'>>>(
    `/api/v1/stations/${encodeURIComponent(id)}`, { signal },
  );
  return unwrapApiData(data);
}

export async function fetchServiceMode(signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<{ mode: unknown }>>('/api/v1/status', { signal });
  const { mode } = unwrapApiData(data);
  if (mode !== 'infra' && mode !== 'sample') throw new Error('Invalid service data mode');
  return mode;
}

export async function fetchInventoryHistory(stationId: string, startTime: string, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<InventoryRecord[]>>(
    `/api/v1/stations/${encodeURIComponent(stationId)}/inventory-history`,
    { params: { startTime, includePrevious: true }, signal },
  );
  return unwrapApiData(data);
}

export async function fetchBatchStations(ids: string[], center: Coordinates, signal?: AbortSignal) {
  const { data } = await apiClient.post<ApiEnvelope<Station[]>>(
    '/api/v1/stations/batch', { stationIds: ids, ...center }, { signal },
  );
  return unwrapApiData(data);
}

export async function searchStations(query: string, center: Coordinates, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Station[]>>('/api/v1/stations/search', {
    params: { query, ...center }, signal,
  });
  return unwrapApiData(data);
}

export async function searchPlaces(query: string, center: Coordinates, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Place[]>>('/api/v1/places/search', {
    params: { query, ...center }, signal,
  });
  return unwrapApiData(data);
}

export async function fetchNearbySubways(center: Coordinates, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Place[]>>('/api/v1/places/nearby-subways', { params: center, signal });
  return unwrapApiData(data);
}

export async function reversePlace(coordinate: Coordinates, signal?: AbortSignal) {
  const { data } = await apiClient.get<ApiEnvelope<Place | null>>('/api/v1/places/reverse', { params: coordinate, signal });
  return unwrapApiData(data);
}
