export interface ProjectedStation {
  id: string;
  x: number;
  y: number;
}
export interface MarkerGroup {
  id: string;
  x: number;
  y: number;
  stationIds: string[];
  offsetX?: number;
  offsetY?: number;
}

export function clusterPoints(
  points: ProjectedStation[],
  selectedId: string | null,
  markerWidth = 72,
  markerHeight = 48,
): MarkerGroup[] {
  const buckets: ProjectedStation[][] = [];
  for (const point of [...points].sort((a, b) => a.id.localeCompare(b.id))) {
    // All members must overlap each other. Do not merge long chains or grid cells.
    const bucket = point.id === selectedId ? undefined : buckets.find(items => items.every(other =>
      other.id !== selectedId && Math.abs(point.x - other.x) < markerWidth && Math.abs(point.y - other.y) < markerHeight));
    if (bucket) bucket.push(point);
    else buckets.push([point]);
  }
  return buckets.map(bucket => ({
    id: bucket.map(point => point.id).join(','),
    x: bucket.reduce((sum, point) => sum + point.x, 0) / bucket.length,
    y: bucket.reduce((sum, point) => sum + point.y, 0) / bucket.length,
    stationIds: bucket.map((point) => point.id),
  }));
}

export function separateMarkerGroups(
  groups: MarkerGroup[], selectedId: string | null, width = 72, height = 48,
): MarkerGroup[] {
  const placed: {x: number; y: number}[] = [];
  return [...groups]
    .sort((a, b) => Number(b.stationIds.includes(selectedId ?? '')) - Number(a.stationIds.includes(selectedId ?? '')))
    .map(group => {
      const candidates = [{x: 0, y: 0}];
      for (let ring = 1; ring <= 3; ring++) {
        for (let x = -ring; x <= ring; x++) for (let y = -ring; y <= ring; y++) {
          if (Math.max(Math.abs(x), Math.abs(y)) === ring) candidates.push({x: x * (width + 6), y: y * (height + 6)});
        }
      }
      candidates.sort((a, b) => Math.hypot(a.x, a.y) - Math.hypot(b.x, b.y));
      const offset = candidates.find(candidate => placed.every(other =>
        Math.abs(group.x + candidate.x - other.x) >= width + 4 ||
        Math.abs(group.y + candidate.y - other.y) >= height + 4)) ?? {x: 0, y: 0};
      placed.push({x: group.x + offset.x, y: group.y + offset.y});
      return {...group, offsetX: offset.x, offsetY: offset.y};
    });
}
