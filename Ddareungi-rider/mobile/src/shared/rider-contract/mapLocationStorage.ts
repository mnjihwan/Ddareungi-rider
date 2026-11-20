import { displayStationName } from './stationName.ts';
import type { Coordinates } from './types.ts';

export const MAP_LOCATION_KEY = 'ddareungrider:last-map-location:v1';
export interface SavedMapLocation { location: Coordinates; label: string }

export function parseMapLocation(raw: string | null): SavedMapLocation | null {
  try {
    const value = JSON.parse(raw ?? 'null');
    const lat = value?.location?.latitude, lng = value?.location?.longitude;
    if (!Number.isFinite(lat) || !Number.isFinite(lng) || Math.abs(lat) > 90 || Math.abs(lng) > 180 ||
        typeof value.label !== 'string' || !value.label.trim() || value.label.length > 200) return null;
    return { location: { latitude: lat, longitude: lng }, label: value.label };
  } catch { return null; }
}

export function displaySavedMapLabel(saved: SavedMapLocation, stations: Iterable<{
  stationName: string; stationLatitude: number | null; stationLongitude: number | null;
}>): string {
  const suffix = ' 주변';
  if (!saved.label.endsWith(suffix)) return saved.label;
  const name = saved.label.slice(0, -suffix.length);
  const displayName = displayStationName(name);
  if (displayName === name) return saved.label;
  // Old entries have no source type. Require an exact station match before changing the label.
  for (const station of stations) {
    if (station.stationName === name && station.stationLatitude === saved.location.latitude &&
        station.stationLongitude === saved.location.longitude) return displayName + suffix;
  }
  return saved.label;
}
