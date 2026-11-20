import { availability } from './status.ts';
import type { Station } from './types.ts';

const DAY = 86_400_000;
const KST = 9 * 60 * 60_000;
export const INVENTORY_OFFSETS = [0, 1, 7] as const;
export const TIME_OPTIONS = Array.from({ length: 288 }, (_, index) => {
  const minutes = index * 5;
  return `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`;
});

// Only exact, timezone-qualified observed slots belong here.
export interface InventoryRecord {
  stationId: string;
  observedSlot: string;
  availableBikes: number;
}

type ComparedStation = Pick<Station, 'stationId' | 'parkingBikeTotCnt' | 'realtimeUpdatedAt' | 'realtimeStatus'>;

export function comparisonRows(station: ComparedStation, selection: string, now: number, records: InventoryRecord[] = [], sample = false) {
  const midnight = Math.floor((now + KST) / DAY) * DAY - KST;
  const currentSlot = Math.floor(now / 300_000) * 300_000;
  const selectedIndex = TIME_OPTIONS.indexOf(selection);
  const live = selection === 'now';
  const stock = availability(station, now);
  const start = live || selectedIndex < 0 ? currentSlot : midnight + selectedIndex * 300_000;
  return [-10, -5, 0, 5, 10, 15].map(minutes => {
    const timestamp = start + minutes * 60_000;
    const shifted = new Date(timestamp + KST);
    const time = shifted.toISOString().slice(11, 16);
    return {
      minutes, time, previousDay: timestamp < midnight, nextDay: timestamp >= midnight + DAY,
      cells: INVENTORY_OFFSETS.map(days => {
        const target = timestamp - days * DAY;
        const isLive = live && minutes === 0 && days === 0;
        const record = records.find(value => value.stationId === station.stationId
          && /(?:Z|[+-]\d{2}:\d{2})$/.test(value.observedSlot)
          && Date.parse(value.observedSlot) === target);
        const observed = record && Number.isInteger(record.availableBikes) && record.availableBikes >= 0
          ? record.availableBikes : null;
        const count = sample || target > now ? null : isLive
          ? stock === 'unknown' || stock === 'stale' ? null : station.parkingBikeTotCnt
          : observed;
        return { count, isLive, isFuture: target > now, date: new Date(target + KST).toISOString().slice(0, 10) };
      }),
    };
  });
}
