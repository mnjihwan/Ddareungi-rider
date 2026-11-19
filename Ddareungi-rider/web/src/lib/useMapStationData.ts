import { useQuery } from "@tanstack/react-query";
import { stationClusters, viewportStations } from "./api";
import { clusterLevel, clusterRequestBounds, mapQueryMode } from "./mapClusters";
import type { MapBounds } from "./types";

/**
 * Phase 3 R4 map data, moved out of App.tsx unchanged: levels 1..4 load individual stations (`/stations/viewport`),
 * levels 5..8 server grid clusters (`/stations/clusters`) for a cell-aligned padded box. Query keys, refetch
 * intervals and enable conditions are the same as before the move.
 */
export function useMapStationData({ viewport, mapLevel, mapReady, hideStationMarkers }: {
  viewport: MapBounds | null;
  mapLevel: number | null;
  mapReady: boolean;
  hideStationMarkers: boolean;
}) {
  const mapMode = mapQueryMode(mapLevel);
  const clusterBox = viewport && mapMode === "clusters" ? clusterRequestBounds(viewport, mapLevel!) : null;
  const mapStations = useQuery({
    queryKey: ["viewport", viewport],
    queryFn: ({signal}) => viewportStations(viewport!, signal),
    enabled: !!viewport && mapReady && mapMode === "viewport",
    placeholderData: previous => previous,
    staleTime: 30000,
    refetchInterval: 60000,
  });
  const mapClusters = useQuery({
    queryKey: ["clusters", clusterBox, mapLevel === null ? null : clusterLevel(mapLevel)],
    queryFn: ({signal}) => stationClusters(clusterBox!, clusterLevel(mapLevel!), signal),
    enabled: !!clusterBox && mapReady && !hideStationMarkers,
    placeholderData: previous => previous,
    staleTime: 30000,
    refetchInterval: 60000,
  });
  const activeMapQuery = mapMode === "clusters" ? mapClusters : mapStations;
  return { mapMode, mapStations, mapClusters, activeMapQuery };
}
