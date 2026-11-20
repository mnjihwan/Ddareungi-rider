import AsyncStorage from '@react-native-async-storage/async-storage';
import { MAP_LOCATION_KEY, parseMapLocation } from '../../../shared/rider-contract/mapLocationStorage';
import type { Coordinates } from '../../../shared/rider-contract/types';

let latestWrite = Promise.resolve();

export async function loadLastMap() {
  try {
    await latestWrite;
    return parseMapLocation(await AsyncStorage.getItem(MAP_LOCATION_KEY));
  } catch {
    return null;
  }
}

export function saveLastMap(location: Coordinates, label: string) {
  const value = parseMapLocation(JSON.stringify({ location, label }));
  if (!value) return;
  latestWrite = latestWrite
    .then(() => AsyncStorage.setItem(MAP_LOCATION_KEY, JSON.stringify(value)))
    .catch(() => {});
}
