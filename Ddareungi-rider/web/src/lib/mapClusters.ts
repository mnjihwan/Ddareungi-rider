// Map data by zoom level (phase 3 R4). Kakao levels 1..4 show individual stations from `/stations/viewport`;
// levels 5..8 show server grid clusters from `/stations/clusters`. Pure functions so node tests load them directly.
import { clusterPoints } from "./clusters.ts";
import type { ApiMeta } from "./apiEnvelope.ts";
import type { MapBounds, StationClusterItem } from "./types.ts";

export const CLUSTER_MIN_LEVEL = 5;
export const CLUSTER_MAX_LEVEL = 8;
/** Server defaults of `station.map.cluster-cell-degrees` (StationClusterResponse); used to align the request box. */
export const CLUSTER_CELL_DEGREES: Record<number, number> = { 5: 0.003, 6: 0.005, 7: 0.01, 8: 0.02 };
/** The server rejects a box wider than 1° in latitude or longitude; stay a little below it. */
export const MAX_BOX_DEGREES = 0.99;

export type MapQueryMode = "viewport" | "clusters";

export function mapQueryMode(level: number | null | undefined): MapQueryMode {
  return typeof level === "number" && level >= CLUSTER_MIN_LEVEL ? "clusters" : "viewport";
}

/** The level sent to `/stations/clusters` (5..8); Kakao is configured with maxLevel 8, this only guards the range. */
export function clusterLevel(level: number): number {
  return Math.min(CLUSTER_MAX_LEVEL, Math.max(CLUSTER_MIN_LEVEL, Math.round(level)));
}

const round6 = (value: number) => Math.round(value * 1e6) / 1e6;
const clamp = (value: number, min: number, max: number) => Math.min(max, Math.max(min, value));

function limitSpan(low: number, high: number, min: number, max: number): [number, number] {
  if (high - low <= MAX_BOX_DEGREES) return [low, high];
  const middle = (low + high) / 2;
  return [clamp(middle - MAX_BOX_DEGREES / 2, min, max), clamp(middle + MAX_BOX_DEGREES / 2, min, max)];
}

/**
 * Request box for the clusters query. The server only aggregates stations inside the box, so a cell cut by the
 * box edge would show a partial count (StationClusterResponse). The visible box is padded by one cell on every side
 * and snapped outward to the cell grid (anchored at 0,0), which makes edge cells complete and keeps the query key
 * stable while the map pans within a cell. The result never exceeds the 1° limit (it is shrunk around the centre).
 */
export function clusterRequestBounds(bounds: MapBounds, level: number): MapBounds {
  const cell = CLUSTER_CELL_DEGREES[clusterLevel(level)] ?? CLUSTER_CELL_DEGREES[CLUSTER_MAX_LEVEL];
  const down = (value: number) => round6(Math.floor(round6(value / cell) - 1) * cell);
  const up = (value: number) => round6(Math.ceil(round6(value / cell) + 1) * cell);
  const [south, north] = limitSpan(clamp(down(bounds.south), -90, 90), clamp(up(bounds.north), -90, 90), -90, 90);
  const [west, east] = limitSpan(clamp(down(bounds.west), -180, 180), clamp(up(bounds.east), -180, 180), -180, 180);
  return { south: round6(south), north: round6(north), west: round6(west), east: round6(east) };
}

/** Viewport responses cut at the server cap (500): `meta.partial` with RESULT_TRUNCATED. */
export function isTruncated(meta: ApiMeta | undefined): boolean {
  return meta?.partial === true && (meta.warnings ?? []).some(warning => warning.code === "RESULT_TRUNCATED");
}

/** Clicking a cluster zooms in two levels, but never stops above level 4 where individual stations show. */
export function clusterZoomLevel(level: number): number {
  return Math.max(CLUSTER_MIN_LEVEL - 1, level - 2);
}

export interface ProjectedCluster extends StationClusterItem { x: number; y: number }
export interface ClusterMarker {
  id: string;
  latitude: number;
  longitude: number;
  stationCount: number;
  parkingBikeTotCnt: number;
  freshCount: number;
  /** Number of server cells merged into this marker. */
  cells: number;
}

/**
 * Neighbouring cells' mean positions can be closer than a marker is wide. The server returns at most a few hundred
 * cells for one screen, so the existing client overlap grouping (`clusterPoints`, O(n²)) is affordable here: cells
 * whose markers overlap are merged and their counts summed; the position is the station-weighted mean.
 */
export function mergeOverlappingClusters(items: ProjectedCluster[], markerWidth = 72, markerHeight = 48): ClusterMarker[] {
  const byId = new Map(items.map((item, index) => [`c${index}`, item]));
  return clusterPoints([...byId].map(([id, item]) => ({ id, x: item.x, y: item.y })), null, markerWidth, markerHeight)
    .map(group => {
      const members = group.stationIds.map(id => byId.get(id)!);
      const stationCount = members.reduce((sum, item) => sum + item.stationCount, 0);
      const weight = (item: StationClusterItem) => stationCount > 0 ? item.stationCount / stationCount : 1 / members.length;
      return {
        id: members.map(item => `${item.latitude},${item.longitude}`).sort().join("|"),
        latitude: members.reduce((sum, item) => sum + item.latitude * weight(item), 0),
        longitude: members.reduce((sum, item) => sum + item.longitude * weight(item), 0),
        stationCount,
        parkingBikeTotCnt: members.reduce((sum, item) => sum + item.parkingBikeTotCnt, 0),
        freshCount: members.reduce((sum, item) => sum + item.freshCount, 0),
        cells: members.length,
      };
    });
}
