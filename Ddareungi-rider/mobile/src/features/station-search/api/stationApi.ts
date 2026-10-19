import { Station } from '../../../entities/station/model/types';
import { apiClient } from '../../../shared/api/client';

export async function fetchNearbyStations(latitude: number, longitude: number): Promise<Station[]> {
  const response = await apiClient.get<{ data: Station[] }>('/api/v1/stations/nearby', {
    params: { latitude, longitude, radiusMeters: 1500 },
  });
  return response.data.data;
}
