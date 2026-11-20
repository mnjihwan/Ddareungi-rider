import { apiClient } from '../../../shared/api/client.ts';
import type { Forecast } from '../../../shared/rider-contract/types.ts';

export const FORECAST_HORIZONS = [5, 10, 15, 30, 60] as const;
const BATCH_SIZE = 100;

function forecastEndpoint(configuredPath?: string): string | undefined {
  const path = configuredPath?.trim();
  if (!path || !/^(?:\/[\w.~\-]+)+\/?$/.test(path)) return undefined;
  if (path.split('/').some((segment) => segment === '.' || segment === '..')) return undefined;
  return `/api/v1${path}`;
}

export const forecastPath = forecastEndpoint(process.env.EXPO_PUBLIC_FORECAST_API_PATH);

export function forecastStationIds(stationIds: readonly string[]): string[] {
  return [...new Set(stationIds)].sort();
}

function isZonedTime(value: unknown): value is string {
  return typeof value === 'string' && /(?:Z|[+-]\d{2}:\d{2})$/i.test(value) && Number.isFinite(Date.parse(value));
}

export async function loadForecasts(stationIds: readonly string[], signal?: AbortSignal): Promise<Forecast[]> {
  if (!forecastPath) throw new Error('Forecast service unavailable');
  const ids = forecastStationIds(stationIds);
  const forecasts: Forecast[] = [];
  for (let offset = 0; offset < ids.length; offset += BATCH_SIZE) {
    const batch = ids.slice(offset, offset + BATCH_SIZE);
    const { data } = await apiClient.post<{ data: Forecast[] }>(forecastPath, { stationIds: batch, horizons: FORECAST_HORIZONS }, { signal });
    if (!Array.isArray(data?.data)) throw new Error('Invalid forecast response');
    for (const forecast of data.data) {
      if (!forecast || !batch.includes(forecast.stationId) || !isZonedTime(forecast.baseTime) || !Array.isArray(forecast.predictions)) continue;
      forecasts.push({
        ...forecast,
        predictions: forecast.predictions.filter((point) => point && isZonedTime(point.targetTime)),
      });
    }
  }
  return forecasts;
}

export async function loadForecast(stationId: string, signal?: AbortSignal): Promise<Forecast> {
  const forecasts = await loadForecasts([stationId], signal);
  const forecast = forecasts.find((value) => value.stationId === stationId);
  if (!forecast) throw new Error('Forecast unavailable');
  return forecast;
}
