import type {Coordinates} from "./types";
import {MAP_LOCATION_KEY, parseMapLocation, type SavedMapLocation} from "../../../mobile/src/shared/rider-contract/mapLocationStorage.ts";

export * from "../../../mobile/src/shared/rider-contract/mapLocationStorage.ts";
export function loadMapLocation(): SavedMapLocation | null {
  try { return parseMapLocation(localStorage.getItem(MAP_LOCATION_KEY)); } catch { return null; }
}
export function saveMapLocation(location: Coordinates, label: string): void {
  try {
    const value = parseMapLocation(JSON.stringify({location, label}));
    if (value) localStorage.setItem(MAP_LOCATION_KEY, JSON.stringify(value));
  } catch { /* Storage may be disabled or full; map interaction must still work. */ }
}
