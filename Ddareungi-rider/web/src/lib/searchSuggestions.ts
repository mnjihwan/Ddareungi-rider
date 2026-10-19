import type { Place, Station } from './types.ts';
import {bestSearchScore, searchScore} from './koreanSearch.ts';

export type SearchSuggestion =
  | {key: string; kind: 'place'; name: string; address: string; place: Place}
  | {key: string; kind: 'station'; name: string; address: string; station: Station};

export function buildSearchSuggestions(places: Place[], stations: Station[], query = ''): SearchSuggestion[] {
  const scores = [...places.map(place => bestSearchScore(place.name, place.address, query)),
    ...stations.map(station => bestSearchScore(station.stationName, station.address, query))];
  const strong = scores.some(score => score >= 0 && score <= 2);
  const matches = (name: string, address: string) => {
    const score = bestSearchScore(name, address, query);
    return score >= 0 && (!strong || score <= 2);
  };
  const distinct = [...new Map(places.filter(place => matches(place.name, place.address)).map(place => [place.sourceUrl ??
    `${place.name}|${place.location.latitude}|${place.location.longitude}`, place])).values()];
  const rank = (place: Place) => bestSearchScore(place.name, place.address, query);
  distinct.sort((a, b) => rank(a) - rank(b));
  const matchingSubway = (place: Place) => place.categoryGroupCode === 'SW8' && searchScore(place.name, query) >= 0;
  const subways = distinct.filter(matchingSubway);
  const others = distinct.filter(place => !matchingSubway(place));
  const asSuggestion = (place: Place): SearchSuggestion => ({key: `place:${place.sourceUrl ?? `${place.name}|${place.location.latitude}|${place.location.longitude}`}`,
    kind: 'place', name: place.name, address: place.address, place});
  const stationSuggestions: SearchSuggestion[] = [...new Map(stations.filter(station => station.stationName?.trim() && matches(station.stationName, station.address))
    .map(station => [station.stationId, station])).values()]
    .sort((a, b) => bestSearchScore(a.stationName, a.address, query) - bestSearchScore(b.stationName, b.address, query) || a.distanceMeters - b.distanceMeters)
    .slice(0, 3).map(station => ({key: `station:${station.stationId}`, kind: 'station', name: station.stationName,
      address: station.address, station}));
  // Keep room for our rental stations even when the provider returns many businesses.
  return [...subways.slice(0, 3).map(asSuggestion), ...others.slice(0, 4).map(asSuggestion),
    ...stationSuggestions, ...others.slice(4).map(asSuggestion)].slice(0, 10)
    .sort((a, b) => bestSearchScore(a.name, a.address, query) - bestSearchScore(b.name, b.address, query));
}

export function matchingParts(name: string, query: string): {text: string; matched: boolean}[] {
  const term = query.trim();
  if (!term) return [{text: name, matched: false}];
  const index = name.toLocaleLowerCase().indexOf(term.toLocaleLowerCase());
  if (index < 0) return [{text: name, matched: false}];
  return [{text: name.slice(0, index), matched: false}, {text: name.slice(index, index + term.length), matched: true},
    {text: name.slice(index + term.length), matched: false}].filter(part => part.text);
}
