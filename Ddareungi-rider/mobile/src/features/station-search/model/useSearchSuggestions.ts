import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { hasStationLocation } from '../../../entities/station/model/types';
import { buildSearchSuggestions } from '../../../shared/rider-contract/searchSuggestions';
import type { Coordinates } from '../../../shared/rider-contract/types';
import { searchPlaces, searchStations } from '../api/stationApi';

export function useSearchSuggestions(input: string, center: Coordinates, submitted = false) {
  const term = input.trim();
  const [debounced, setDebounced] = useState('');
  useEffect(() => {
    const timer = setTimeout(() => setDebounced(term), 350);
    return () => clearTimeout(timer);
  }, [term]);

  const searching = term.length >= 2 && term.length <= 100;
  const enabled = searching && (submitted || term === debounced);
  const places = useQuery({
    queryKey: ['place-search', term, center],
    queryFn: ({ signal }) => searchPlaces(term, center, signal),
    enabled,
    staleTime: 30_000,
    retry: false,
  });
  const stations = useQuery({
    queryKey: ['station-search', term, center],
    queryFn: ({ signal }) => searchStations(term, center, signal),
    enabled,
    staleTime: 30_000,
    retry: false,
  });
  return {
    term,
    enabled,
    pending: searching && (!enabled || places.isPending || stations.isPending),
    results: enabled && !submitted ? buildSearchSuggestions(places.data ?? [], (stations.data ?? []).filter(hasStationLocation), term) : [],
    places,
    stations,
  };
}
