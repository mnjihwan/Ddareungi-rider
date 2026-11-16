import type { RealtimeStatus, Station } from "./types";

/** "stale": last known count is shown but the data is delayed; never counted as rentable. */
export type Availability = "available" | "low" | "empty" | "stale" | "unknown";
export const AVAILABILITY_LABEL: Record<Availability, string> = {
  available: "대여 가능",
  low: "조금 남았어요",
  empty: "대여 불가",
  stale: "갱신 지연",
  unknown: "정보 확인 중",
};

/** Same boundaries as the backend defaults (spec 11.0, X3); used only when the server status is missing. */
export const FRESH_SECONDS = 120;
export const MAX_STALE_SECONDS = 600;
const MAX_CLOCK_SKEW_MS = 30_000;

type StationStatusFields = Pick<Station, "realtimeUpdatedAt"> & Partial<Pick<Station, "realtimeStatus">>;

/**
 * Freshness for display. The server's `realtimeStatus` decides FRESH vs STALE. The client clock only downgrades to
 * UNAVAILABLE (a response kept on screen beyond max-stale, or a collection time more than 30s in the future), and
 * classifies by age when an older response has no `realtimeStatus`.
 */
export function realtimeStatus(station: StationStatusFields, now = Date.now()): RealtimeStatus {
  const updated = station.realtimeUpdatedAt ? Date.parse(station.realtimeUpdatedAt) : NaN;
  if (!Number.isFinite(updated) || updated - now > MAX_CLOCK_SKEW_MS) return "UNAVAILABLE";
  const ageSeconds = Math.max(0, Math.floor((now - updated) / 1000));
  if (ageSeconds > MAX_STALE_SECONDS || station.realtimeStatus === "UNAVAILABLE") return "UNAVAILABLE";
  if (station.realtimeStatus === "FRESH" || station.realtimeStatus === "STALE") return station.realtimeStatus;
  return ageSeconds <= FRESH_SECONDS ? "FRESH" : "STALE";
}

export function availability(station: StationStatusFields & Pick<Station, "parkingBikeTotCnt">, now = Date.now()): Availability {
  const status = realtimeStatus(station, now);
  const count = station.parkingBikeTotCnt;
  if (status === "UNAVAILABLE" || count === null || !Number.isInteger(count) || count < 0) return "unknown";
  if (status === "STALE") return "stale";
  return count === 0 ? "empty" : count <= 3 ? "low" : "available";
}

/** Rentable stations first; zero stock second; delayed (stale) or unavailable data last. */
const AVAILABILITY_RANK: Record<Availability, number> = { available: 0, low: 0, empty: 1, stale: 2, unknown: 2 };

export function compareStations(a: Station, b: Station, now = Date.now()): number {
  return AVAILABILITY_RANK[availability(a, now)] - AVAILABILITY_RANK[availability(b, now)]
    || a.distanceMeters - b.distanceMeters;
}

export function distanceLabel(meters: number): string {
  return meters >= 1000
    ? `${(meters / 1000).toFixed(1)}km`
    : `${Math.round(meters)}m`;
}

export function observationLabel(station: Station, now = Date.now()): string {
  if (!station.realtimeUpdatedAt) return "수량 정보 없음";
  const updated = Date.parse(station.realtimeUpdatedAt);
  if (!Number.isFinite(updated) || updated - now > MAX_CLOCK_SKEW_MS) return "갱신 시각 확인 중";
  const age = Math.max(0, Math.floor((now - updated) / 60_000));
  const status = realtimeStatus(station, now);
  if (status === "UNAVAILABLE") return "갱신 지연";
  const when = age <= 0 ? "방금 업데이트" : `${age}분 전 업데이트`;
  return status === "STALE" ? `${when} · 갱신 지연` : when;
}

export function forecastAt(
  forecast: import("./types").Forecast,
  minutes: number,
  now = Date.now(),
) {
  if (
    forecast.dataStatus !== "fresh" ||
    now - Date.parse(forecast.baseTime) > 10 * 60_000 ||
    !Number.isFinite(Date.parse(forecast.baseTime)) ||
    Date.parse(forecast.baseTime) > now + 30_000
  )
    return null;
  const target = now + minutes * 60_000;
  if (!Array.isArray(forecast.predictions)) return null;
  const closest = [...forecast.predictions]
    .filter(
      (point) =>
        point &&
        Number.isFinite(point.expectedBikes) && point.expectedBikes >= 0 &&
        Number.isFinite(Date.parse(point.targetTime)),
    )
    .sort(
      (a, b) =>
        Math.abs(Date.parse(a.targetTime) - target) -
        Math.abs(Date.parse(b.targetTime) - target),
    )[0];
  if (
    !closest ||
    Math.abs(Date.parse(closest.targetTime) - target) > 2.5 * 60_000
  )
    return null;
  return {
    count: Math.max(0, Math.round(closest.expectedBikes)),
    targetTime: closest.targetTime,
  };
}

/** Prediction mode uses only validated forecasts; missing forecasts sort last. */
export function stationOutlook(station: Station, forecast: import("./types").Forecast | undefined, minutes: number, now = Date.now()) {
  if (minutes === 0) {
    const state = availability(station, now);
    return {state, count: state === "unknown" ? null : station.parkingBikeTotCnt};
  }
  const prediction = forecast?.stationId === station.stationId ? forecastAt(forecast, minutes, now) : null;
  const count = prediction?.count ?? null;
  const state: Availability = count === null ? "unknown" : count === 0 ? "empty" : count <= 3 ? "low" : "available";
  return {state, count};
}
export function compareOutlooks(a: Station, b: Station, forecasts: Map<string, import("./types").Forecast>, minutes: number, now = Date.now()) {
  return AVAILABILITY_RANK[stationOutlook(a, forecasts.get(a.stationId), minutes, now).state]
    - AVAILABILITY_RANK[stationOutlook(b, forecasts.get(b.stationId), minutes, now).state]
    || a.distanceMeters - b.distanceMeters;
}
