import type { LabelScene, MapImageSource, RouteScene } from '@react-native-kakao/map';
import type { Coordinates } from '../../../shared/types/coordinates';

export interface MapMarker {
  id: string;
  coordinate: Coordinates;
  text: string;
  kind: 'station' | 'selected' | 'rental' | 'return' | 'origin' | 'destination' | 'location' | 'place' | 'cluster';
  stock?: MarkerStock;
  highlighted?: boolean;
  subtext?: string;
  offset?: { x: number; y: number };
}

export type MarkerStock = 'ready' | 'low' | 'empty' | 'unknown' | 'stale';
export type MarkerAssets = Record<MarkerStock | 'selected' | 'location' | 'rental' | 'return' | 'origin' | 'destination' | 'cluster', MapImageSource>;

export interface MapRouteSegment {
  type: 'WALK' | 'BIKE';
  path: Coordinates[];
}

export const ROUTE_COLORS = { WALK: '#2563A6', BIKE: '#0087A8' };

/**
 * Rental/return tag for the route's own stations, matched by ID; any other station (such as an extra selection)
 * keeps its normal badge. `routeStations` is [rental, return].
 */
export function routeStationRole(
  stationId: string, routeStations?: readonly { stationId: string }[],
): 'rental' | 'return' | undefined {
  const [rental, arrival] = routeStations ?? [];
  if (stationId === rental?.stationId) return 'rental';
  if (stationId === arrival?.stationId) return 'return';
  return undefined;
}

const markerColors: Record<MapMarker['kind'], string> = {
  station: '#395780',
  selected: '#203C60',
  rental: '#00758F',
  return: '#00758F',
  origin: '#203C60',
  destination: '#203C60',
  location: '#2563A6',
  place: '#203C60',
  cluster: '#2563A6',
};

const markerStyles: NonNullable<LabelScene['styles']> = Object.entries(markerColors).map(([id, color]) => ({
  id,
  levels: [{
    level: 0,
    textLines: [{
      fontSize: id === 'station' ? 15 : 16,
      color,
      strokeColor: '#FFFFFF',
      strokeWidth: 3,
    }],
  }],
}));

export const STATION_LAYER = 'stations';
const EMPHASIS_LAYER = 'emphasis';
const STOCK_LAYER = 'station-stock';
const SELECTED_STOCK_LAYER = 'selected-stock';
const ROUTE_TAG_LAYER = 'route-tags';

export function markerScene(markers: MapMarker[], assets?: MarkerAssets, dark = false): LabelScene {
  const stockColors = {
    ready: dark ? '#8FBFFF' : '#395780',
    low: dark ? '#E6C17C' : '#A97922',
    empty: dark ? '#F1A599' : '#B56D5A',
    unknown: dark ? '#C4C4CC' : '#87909E',
    stale: dark ? '#C4C4CC' : '#87909E',
    selected: '#FFFFFF',
  };
  const assetStyles: NonNullable<LabelScene['styles']> = assets ? [
    ...Object.entries(assets).map(([id, image]) => ({
      id: `badge-${id}`,
      levels: [{ level: 0, image, anchor: { x: 0.5, y: id === 'location' || id === 'cluster' ? 0.5 : id === 'rental' || id === 'return' ? 0 : 1 } }],
    })),
    ...Object.entries(stockColors).map(([id, color]) => ({
      id: `count-${id}`, levels: [{ level: 0, textLines: [{ fontSize: 15, color }] }],
    })),
    { id: 'cluster-count', levels: [{ level: 0, textLines: [{ fontSize: 19, color: '#FFFFFF' }] }] },
    { id: 'cluster-stock', levels: [{ level: 0, textLines: [{ fontSize: 9, color: '#FFFFFF' }] }] },
  ] : [];

  return {
    styles: [...markerStyles, ...assetStyles],
    layers: [
      { id: STATION_LAYER, zOrder: 1000, clickable: true, competition: 'none' },
      { id: STOCK_LAYER, zOrder: 1001, clickable: true, competition: 'none' },
      { id: EMPHASIS_LAYER, zOrder: 1002, clickable: true, competition: 'none' },
      { id: SELECTED_STOCK_LAYER, zOrder: 1003, clickable: true, competition: 'none' },
      { id: ROUTE_TAG_LAYER, zOrder: 1004, clickable: false, competition: 'none' },
    ],
    labels: markers.flatMap(({ id, coordinate, kind, text, stock, highlighted, subtext, offset }): NonNullable<LabelScene['labels']> => {
      const layerId = kind === 'station' ? STATION_LAYER : EMPHASIS_LAYER;
      if (assets && kind === 'cluster') return [
        { id, coordinate, layerId: STATION_LAYER, styleId: 'badge-cluster', clickable: true },
        { id: `cluster-count:${id}`, coordinate, layerId: STOCK_LAYER, styleId: 'cluster-count', clickable: true,
          pixelOffset: { x: 0, y: -7 }, text: [{ value: text }] },
        { id: `cluster-stock:${id}`, coordinate, layerId: STOCK_LAYER, styleId: 'cluster-stock', clickable: true,
          pixelOffset: { x: 0, y: 9 }, text: [{ value: subtext ?? '' }] },
      ];
      if (assets && (kind === 'location' || kind === 'origin' || kind === 'destination')) {
        return [{ id, coordinate, layerId: kind === 'location' ? layerId : ROUTE_TAG_LAYER, styleId: `badge-${kind}`, clickable: false }];
      }
      const routeStation = kind === 'rental' || kind === 'return';
      if (assets && (kind === 'station' || kind === 'selected' || routeStation)) {
        const isSelected = kind === 'selected' || highlighted;
        const key = isSelected ? 'selected' : stock ?? 'unknown';
        return [
          { id, coordinate, layerId, styleId: `badge-${key}`, pixelOffset: offset, clickable: true },
          { id: `count:${id}`, coordinate, layerId: kind === 'station' ? STOCK_LAYER : SELECTED_STOCK_LAYER,
            styleId: `count-${key}`, pixelOffset: { x: 12 + (offset?.x ?? 0), y: -28 + (offset?.y ?? 0) },
            text: [{ value: text }], clickable: true },
          ...(routeStation ? [{ id: `route-tag:${id}`, coordinate, layerId: ROUTE_TAG_LAYER, styleId: `badge-${kind}`,
            pixelOffset: { x: 0, y: 10 }, clickable: false }] : []),
        ];
      }
      return [{ id, coordinate, layerId, styleId: kind, text: [{ value: text, styleIndex: 0 }],
        clickable: kind === 'station' || kind === 'selected' || kind === 'rental' || kind === 'return' }];
    }),
  };
}

export function routeScene(segments: MapRouteSegment[], dark = false): RouteScene {
  if (!segments.length) return {};
  return {
    styleSets: [{
      id: 'rider-route-styles',
      styles: [
        { levels: [{ level: 0, width: 5, color: dark ? '#92BFFF' : ROUTE_COLORS.WALK }] },
        { levels: [{ level: 0, width: 7, color: dark ? '#5DD5E5' : ROUTE_COLORS.BIKE }] },
      ],
    }],
    layers: [{ id: 'rider-routes' }],
    routes: segments.map(({ type, path }, index) => ({
      id: `segment-${index}`,
      layerId: 'rider-routes',
      styleSetId: 'rider-route-styles',
      segments: [{ coordinates: path, styleIndex: type === 'BIKE' ? 1 : 0 }],
    })),
  };
}
