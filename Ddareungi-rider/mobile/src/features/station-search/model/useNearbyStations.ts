import { useQuery } from '@tanstack/react-query';
import { fetchNearbyStations } from '../api/stationApi';

export function useNearbyStations(latitude: number, longitude: number) {
  return useQuery({
    queryKey: ['nearby-stations', latitude, longitude],
    queryFn: () => fetchNearbyStations(latitude, longitude),
    staleTime: 30_000,
  });
}
