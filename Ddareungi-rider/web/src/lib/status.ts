import type { Station } from "./types";

export type Availability = "available" | "low" | "empty" | "unknown";
export const AVAILABILITY_LABEL: Record<Availability, string> = {
  available: "대여 가능",
  low: "조금 남았어요",
  empty: "대여 불가",
  unknown: "정보 확인 중",
};

export function availability(station: Station, now = Date.now()): Availability {
  const updated = station.updatedAt ? Date.parse(station.updatedAt) : NaN;
  const count = station.availableBikeCount;
  if (
    !Number.isFinite(updated) ||
    now - updated > 5 * 60_000 ||
    updated - now > 30_000 ||
    count === null ||
    !Number.isInteger(count) ||
    count < 0
  )
    return "unknown";
  return count === 0 ? "empty" : count <= 3 ? "low" : "available";
}

export function distanceLabel(meters: number): string {
  return meters >= 1000
    ? `${(meters / 1000).toFixed(1)}km`
    : `${Math.round(meters)}m`;
}

export function observationLabel(station: Station, now = Date.now()): string {
  if (!station.updatedAt) return "수량 정보 없음";
  const age = Math.floor((now - Date.parse(station.updatedAt)) / 60_000);
  if (!Number.isFinite(age) || age < -1) return "갱신 시각 확인 중";
  if (age > 5) return "갱신 지연";
  return age <= 0 ? "방금 업데이트" : `${age}분 전 업데이트`;
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
        Number.isFinite(point.expectedBikes) &&
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
