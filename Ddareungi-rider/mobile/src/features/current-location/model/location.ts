import type { Coordinates } from '../../../shared/rider-contract/types.ts';

export const LOCATION_MESSAGES = {
  permission: '위치 권한을 허용하거나 장소 검색과 지도 선택을 이용해 주세요.',
  services: '기기의 위치 서비스를 켜거나 장소 검색과 지도 선택을 이용해 주세요.',
  unavailable: '현재 위치를 확인하지 못했어요. 다시 시도하거나 출발지를 선택해 주세요.',
  stale: '마지막으로 확인한 위치가 오래됐어요. 현재 위치를 다시 확인하거나 지도에서 선택해 주세요.',
  coarse: '현재 위치의 오차 범위가 커요. 장소를 검색하거나 지도에서 정확한 위치를 선택해 주세요.',
};

export const LOCATION_MAX_AGE_MS = 120_000;
const LOCATION_MAX_ACCURACY_METERS = 500;

export interface LocationFix {
  coordinate: Coordinates;
  timestamp: number;
  accuracy: number | null;
}

export interface LocationLoss {
  reason: 'expired' | 'access';
  message: string;
}

export interface LocationSource {
  requestPermission: () => Promise<boolean>;
  hasServices: () => Promise<boolean>;
  getFix: () => Promise<LocationFix>;
}

export function isCurrentFix(fix: LocationFix, now = Date.now()): boolean {
  const age = now - fix.timestamp;
  return age >= -30_000 && age <= LOCATION_MAX_AGE_MS;
}

export function locationLossOnResume(fix: LocationFix, permitted: boolean, services: boolean, now = Date.now()): LocationLoss | undefined {
  if (!permitted) return { reason: 'access', message: LOCATION_MESSAGES.permission };
  if (!services) return { reason: 'access', message: LOCATION_MESSAGES.services };
  if (!isCurrentFix(fix, now)) return { reason: 'expired', message: LOCATION_MESSAGES.stale };
  return undefined;
}

export async function readLocation(source: LocationSource, signal: AbortSignal, timeout = 12_000): Promise<LocationFix> {
  if (!(await source.requestPermission())) throw new Error(LOCATION_MESSAGES.permission);
  if (signal.aborted) throw new Error(LOCATION_MESSAGES.unavailable);
  if (!(await source.hasServices())) throw new Error(LOCATION_MESSAGES.services);
  if (signal.aborted) throw new Error(LOCATION_MESSAGES.unavailable);

  let timer: ReturnType<typeof setTimeout> | undefined;
  let abort: () => void = () => {};
  try {
    const fix = await Promise.race([
      source.getFix(),
      new Promise<never>((_, reject) => {
        timer = setTimeout(() => reject(new Error(LOCATION_MESSAGES.unavailable)), timeout);
        abort = () => reject(new Error(LOCATION_MESSAGES.unavailable));
        signal.addEventListener('abort', abort, { once: true });
      }),
    ]);
    if (signal.aborted || !Number.isFinite(fix.coordinate.latitude) || !Number.isFinite(fix.coordinate.longitude)) {
      throw new Error(LOCATION_MESSAGES.unavailable);
    }
    if (!isCurrentFix(fix)) throw new Error(LOCATION_MESSAGES.stale);
    if (fix.accuracy === null || !Number.isFinite(fix.accuracy)
      || fix.accuracy < 0 || fix.accuracy > LOCATION_MAX_ACCURACY_METERS) {
      throw new Error(LOCATION_MESSAGES.coarse);
    }
    return fix;
  } finally {
    clearTimeout(timer);
    signal.removeEventListener('abort', abort);
  }
}
