import type { Station } from '../../../entities/station/model/types.ts';

type StationDetail = Omit<Station, 'distanceMeters'>;

function observedAt(value: string | null | undefined): number {
  const time = value ? Date.parse(value) : NaN;
  return Number.isFinite(time) ? time : -Infinity;
}

/**
 * Applies the detail response to the station shown in a list or on the map.
 * Detail and list queries poll independently, so the realtime fields come from whichever observation is newer.
 */
export function mergeStationDetail(station: Station, detail?: StationDetail): Station {
  if (!detail || detail.stationId !== station.stationId) return station;
  const merged: Station = { ...station, ...detail };
  if (observedAt(station.realtimeUpdatedAt) <= observedAt(detail.realtimeUpdatedAt)) return merged;
  return {
    ...merged,
    parkingBikeTotCnt: station.parkingBikeTotCnt,
    realtimeStatus: station.realtimeStatus,
    realtimeUpdatedAt: station.realtimeUpdatedAt,
    realtimeAgeSeconds: station.realtimeAgeSeconds,
  };
}
