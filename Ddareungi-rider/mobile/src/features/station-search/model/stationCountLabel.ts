import type { Station } from '../../../shared/rider-contract/types.ts';
import { availability } from '../../../shared/rider-contract/status.ts';

export function stationCountLabel(
  station: Pick<Station, 'parkingBikeTotCnt' | 'realtimeUpdatedAt' | 'realtimeStatus'>,
  now: number,
  condensed = false,
): string {
  const state = availability(station, now);
  if (state === 'unknown') return condensed ? '?' : '수량 확인 불가';
  return `${station.parkingBikeTotCnt}대${condensed && state === 'stale' ? ' · 지연' : ''}`;
}
