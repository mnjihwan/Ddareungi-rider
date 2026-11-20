import type { Station } from '../../../entities/station/model/types';
import type { Coordinates } from '../../../shared/rider-contract/types.ts';

export interface Endpoint {
  name: string;
  displayName?: string;
  address?: string;
  coordinate: Coordinates;
  stationId?: string;
  station?: Station;
  fromDevice?: boolean;
}

export function asFixedLocation(endpoint?: Endpoint): Endpoint | undefined {
  return endpoint?.fromDevice
    ? { ...endpoint, name: '마지막으로 확인한 위치', fromDevice: false }
    : endpoint;
}
