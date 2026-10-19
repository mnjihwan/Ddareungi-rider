import type {Coordinates} from "./types";
export const MAP_LOCATION_KEY = "ddareungrider:last-map-location:v1";
export interface SavedMapLocation { location: Coordinates; label: string }
export function parseMapLocation(raw: string | null): SavedMapLocation | null {
  try {
    const value = JSON.parse(raw ?? "null");
    const lat = value?.location?.latitude, lng = value?.location?.longitude;
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 ||
        typeof value.label !== "string" || !value.label.trim() || value.label.length > 200) return null;
    return {location: {latitude: lat, longitude: lng}, label: value.label};
  } catch { return null; }
}
export function loadMapLocation(): SavedMapLocation | null {
  try { return parseMapLocation(localStorage.getItem(MAP_LOCATION_KEY)); } catch { return null; }
}
export function saveMapLocation(location: Coordinates, label: string): void {
  try {
    const value = parseMapLocation(JSON.stringify({location, label}));
    if (value) localStorage.setItem(MAP_LOCATION_KEY, JSON.stringify(value));
  } catch { /* Storage may be disabled or full; map interaction must still work. */ }
}
