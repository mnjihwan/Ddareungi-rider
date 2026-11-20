/** Hides a station number prefix while keeping the original name available for search. */
export function displayStationName(name: string): string {
  return name.replace(/^\s*\d+\s*\.(?:\s+(?=\S)|(?=[^\d\s.]))/, '');
}
