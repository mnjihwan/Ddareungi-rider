import type { Place } from './types';

export function placeKey(place: Place): string {
  return place.sourceUrl || `${place.name}|${place.location.latitude}|${place.location.longitude}`;
}
/** Keep actual place labels readable; never infer a shop from a nearby coordinate. */
export function selectVisiblePlaces(points: {place: Place; x: number; y: number}[], width: number, height: number) {
  const occupied: {x: number; y: number; width: number}[] = [];
  const seen = new Set<string>();
  return points.filter(point => {
    const key = placeKey(point.place);
    if (seen.has(key) || !point.place.name.trim() || !point.place.address.trim()) return false;
    const labelWidth = Math.min(144, point.place.name.length * 11 + 24);
    if (point.x-labelWidth/2 < 8 || point.x+labelWidth/2 > width-8 || point.y < 60 || point.y > height-80) return false;
    if (occupied.some(other => Math.abs(other.x-point.x) < (other.width+labelWidth)/2+8 && Math.abs(other.y-point.y) < 38)) return false;
    if (occupied.length >= 16) return false;
    occupied.push({x:point.x,y:point.y,width:labelWidth});seen.add(key);
    return true;
  }).map(point => point.place);
}
