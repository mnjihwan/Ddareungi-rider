import axios from "axios";
import type { Coordinates, Forecast, Place, Station, MapBounds, BikeRoute, NearbyStations } from "./types.ts";
import { unwrap, type ApiEnvelope } from "./apiEnvelope.ts";

export { unwrap, apiError, apiErrorMessage } from "./apiEnvelope.ts";
export type { ApiEnvelope, ApiErrorEnvelope, ApiMeta } from "./apiEnvelope.ts";

export const api = axios.create({ baseURL: "/api/v1", timeout: 10_000 });
export async function planBikeRoute(origin: Coordinates, destination: Coordinates, departureStationId?: string, signal?: AbortSignal, arrivalStationId?: string): Promise<BikeRoute> {
  return unwrap((await api.post<ApiEnvelope<BikeRoute>>("/routes/plan", {origin, destination, departureStationId, arrivalStationId},
    {signal, timeout: 90_000})).data);
}
/** Server maximum of `limit` (spec 11.1). D11: lists may be cut at 100 stations (e.g. the 3km tab). */
export const NEARBY_LIMIT = 100;
/**
 * Nearby stations (spec 11.1, A5): sends `radius` + `limit` (the old `radiusMeters` is no longer read by the
 * server) and unwraps `data.items`, so callers keep receiving `Station[]`.
 */
export async function nearby(
  center: Coordinates,
  radius: number,
  signal?: AbortSignal,
): Promise<Station[]> {
  const data = unwrap((
    await api.get<ApiEnvelope<NearbyStations>>("/stations/nearby", {
      params: { ...center, radius, limit: NEARBY_LIMIT },
      signal,
    })
  ).data);
  if (!data || !Array.isArray(data.items)) throw new Error("Invalid nearby stations response");
  return data.items;
}
export async function viewportStations(bounds: MapBounds, signal?: AbortSignal): Promise<Station[]> {
  return unwrap((await api.get<ApiEnvelope<Station[]>>("/stations/viewport", {params: bounds, signal})).data);
}
export async function favoriteStations(
  ids: string[],
  center: Coordinates,
  signal?: AbortSignal,
): Promise<Station[]> {
  if (!ids.length) return [];
  return unwrap((
    await api.post<ApiEnvelope<Station[]>>(
      "/stations/batch",
      { stationIds: ids, ...center },
      { signal },
    )
  ).data);
}
export async function searchPlaces(
  query: string,
  signal?: AbortSignal,
  center?: Coordinates,
): Promise<Place[]> {
  return unwrap((
    await api.get<ApiEnvelope<Place[]>>("/places/search", {
      params: { query, ...center },
      signal,
    })
  ).data);
}
export async function nearbySubways(center:Coordinates,signal?:AbortSignal):Promise<Place[]> {
  return unwrap((await api.get<ApiEnvelope<Place[]>>('/places/nearby-subways',{params:center,signal})).data);
}
export async function searchStations(
  query: string,
  center: Coordinates,
  signal?: AbortSignal,
): Promise<Station[]> {
  return unwrap((
    await api.get<ApiEnvelope<Station[]>>("/stations/search", {
      params: { query, ...center },
      signal,
    })
  ).data);
}
// `?.` keeps this module importable outside Vite (node tests), where import.meta.env is undefined.
export const forecastPath = import.meta.env?.VITE_FORECAST_API_PATH as
  string | undefined;
export async function loadForecasts(stationIds: string[], signal?: AbortSignal): Promise<Forecast[]> {
  if (!forecastPath || !/^\/(?!\/)/.test(forecastPath)) throw new Error("Forecast service unavailable");
  const ids = [...new Set(stationIds)].sort();
  if (!ids.length) return [];
  const results: Forecast[] = [];
  for (let offset = 0; offset < ids.length; offset += 100) {
    const batch = ids.slice(offset, offset + 100);
    const response = await api.post<{data: Forecast[]}>(forecastPath,
      {stationIds: batch, horizons: [5, 10, 15, 30, 60]}, {signal});
    if (!Array.isArray(response.data.data)) throw new Error("Invalid forecast response");
    results.push(...response.data.data.filter(value => value && batch.includes(value.stationId) && Array.isArray(value.predictions)));
  }
  return results;
}
export async function loadForecast(stationId: string, signal?: AbortSignal): Promise<Forecast> {
  const result = (await loadForecasts([stationId], signal)).find(value => value.stationId === stationId);
  if (!result) throw new Error("Forecast unavailable");
  return result;
}

export async function reversePlace(location: Coordinates, signal?: AbortSignal): Promise<Place | null> {
  return unwrap((await api.get<ApiEnvelope<Place | null>>("/places/reverse", {params: location, signal})).data);
}
