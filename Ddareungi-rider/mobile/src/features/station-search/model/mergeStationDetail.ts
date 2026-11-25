import type { Station } from '../../../entities/station/model/types.ts';

type StationDetail = Omit<Station, 'distanceMeters'>;
type RealtimeFields = Pick<Station, 'parkingBikeTotCnt' | 'realtimeStatus' | 'realtimeUpdatedAt' | 'realtimeAgeSeconds'>;

function observedAt(value: string | null | undefined): number {
  const time = value ? Date.parse(value) : NaN;
  return Number.isFinite(time) ? time : -Infinity;
}

// Copied as one group: a count, its status and its timestamp always describe the same observation.
function realtimeFields(source: RealtimeFields): RealtimeFields {
  return {
    parkingBikeTotCnt: source.parkingBikeTotCnt,
    realtimeStatus: source.realtimeStatus,
    realtimeUpdatedAt: source.realtimeUpdatedAt,
    realtimeAgeSeconds: source.realtimeAgeSeconds,
  };
}

/**
 * Applies the detail response to the station shown in a list or on the map.
 * Detail and list queries poll independently, so the realtime fields come from whichever observation is newer.
 * A detail that reports UNAVAILABLE without a collection time means the server no longer has an observation,
 * which outranks a count the list cached earlier.
 */
export function mergeStationDetail(station: Station, detail?: StationDetail): Station {
  if (!detail || detail.stationId !== station.stationId) return station;
  const detailTime = observedAt(detail.realtimeUpdatedAt);
  const detailWins = detailTime >= observedAt(station.realtimeUpdatedAt)
    || (detail.realtimeStatus === 'UNAVAILABLE' && detailTime === -Infinity);
  return { ...station, ...detail, ...realtimeFields(detailWins ? detail : station) };
}
