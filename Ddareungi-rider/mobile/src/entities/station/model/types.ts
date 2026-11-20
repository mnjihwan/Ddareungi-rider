import type { Coordinates } from '../../../shared/types/coordinates';
import type { Station as LocatedStation } from '../../../shared/rider-contract/types';

// Some server responses omit coordinates for stations with incomplete records.
export interface Station extends Omit<LocatedStation, 'stationLatitude' | 'stationLongitude'> {
  stationLatitude: number | null;
  stationLongitude: number | null;
}

export function hasStationLocation(station: Station): station is LocatedStation {
  return Number.isFinite(station.stationLatitude) && Number.isFinite(station.stationLongitude);
}

export function stationLocation(station: Station): Coordinates | null {
  if (!hasStationLocation(station)) return null;
  const { stationLatitude: latitude, stationLongitude: longitude } = station;
  return { latitude, longitude };
}
