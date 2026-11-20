import type { Station } from '../../../entities/station/model/types.ts';

export function routeStationObservation(station: Station, batch: Station[] | undefined): Station {
  if (!batch) return station;
  const observation = batch.find(({ stationId }) => stationId === station.stationId);
  if (!observation) {
    // The server omits stations without a current observation from station batches.
    return {
      ...station,
      parkingBikeTotCnt: null,
      realtimeStatus: 'UNAVAILABLE',
      realtimeUpdatedAt: null,
      realtimeAgeSeconds: null,
    };
  }
  return {
    ...observation,
    stationLatitude: station.stationLatitude,
    stationLongitude: station.stationLongitude,
    distanceMeters: station.distanceMeters,
  };
}
