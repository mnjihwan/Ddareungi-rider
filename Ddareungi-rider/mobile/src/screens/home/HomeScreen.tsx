import AsyncStorage from '@react-native-async-storage/async-storage';
import { useMutation, useQuery } from '@tanstack/react-query';
import { StatusBar } from 'expo-status-bar';
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { AppState, Image, Keyboard, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { stationLocation, type Station } from '../../entities/station/model/types';
import type { LocationLoss } from '../../features/current-location/model/location';
import { useCurrentLocation } from '../../features/current-location/model/useCurrentLocation';
import { useStationListForecasts } from '../../features/forecast/model/useStationForecasts';
import { requestRouteMappingCheck } from '../../features/rider-map/api/routeMappingCheck';
import { loadLastMap, saveLastMap } from '../../features/rider-map/model/lastMapStorage';
import { type MapMarker, type MapRouteSegment } from '../../features/rider-map/model/scenes';
import { RiderMap, type RiderMapHandle } from '../../features/rider-map/ui/RiderMap';
import { routeStationObservation } from '../../features/route-planning/model/routeStationObservation';
import { type Endpoint, useRoutePlanner } from '../../features/route-planning/model/useRoutePlanner';
import { useSavedItems } from '../../features/saved/model/useSavedItems';
import { fetchNearbySubways, fetchServiceMode, reversePlace } from '../../features/station-search/api/stationApi';
import {
  isSupportedViewport,
  STATION_REFRESH_MS,
  useBatchStations,
  useNearbyStations,
  useStationClusters,
  useStationDetail,
  useViewportStations,
} from '../../features/station-search/model/useNearbyStations';
import { useSearchSuggestions } from '../../features/station-search/model/useSearchSuggestions';
import { describeApiError } from '../../shared/api/client';
import { separateMarkerGroups } from '../../shared/rider-contract/clusters';
import { CLUSTER_MIN_LEVEL, clusterLevel, clusterZoomLevel, mergeOverlappingClusters } from '../../shared/rider-contract/mapClusters';
import { displaySavedMapLabel, type SavedMapLocation } from '../../shared/rider-contract/mapLocationStorage';
import { bestSearchScore } from '../../shared/rider-contract/koreanSearch';
import { matchSearchResults, type SearchSuggestion } from '../../shared/rider-contract/searchSuggestions';
import { displayStationName } from '../../shared/rider-contract/stationName';
import { availability, compareOutlooks, compareStations, distanceLabel, stationOutlook } from '../../shared/rider-contract/status';
import { INITIAL_LOCATION, type Coordinates, type MapBounds, type Place, type RouteEndpointRole } from '../../shared/rider-contract/types';
import { PrivacyNotice } from '../../shared/ui/PrivacyNotice';
import { PEEK_SHEET_HEIGHT, sheetHeights, type SheetLevel } from './ui/geometry';
import { BottomSheet, MapButtons, MapHeader, RiderIcon, type ViewMode } from './ui/MapChrome';
import { SearchField, SearchResults, SubmittedPlaceRow } from './ui/MapSearch';
import { RoutePanel } from './ui/RoutePanel';
import { ListPanel, SheetButton, StationPanel } from './ui/StationPanels';
import { RiderTheme, RIDER_THEME_KEY, riderThemes } from './ui/theme';

const INITIAL_ZOOM = 16;
const DEFAULT_MAP = { location: INITIAL_LOCATION, label: '강남역 주변' };
const EMPTY_CLUSTERS: MapMarker[] = [];
const EMPTY_STATIONS: Station[] = [];

function projectPoint(point: Coordinates, bounds: MapBounds, width: number, height: number) {
  // The map is north-up and bounds span at most one degree. Scale to logical pixels
  // to reuse the web overlap rules, including on iOS where forward projection is unavailable.
  return {
    x: (point.longitude - bounds.west) / (bounds.east - bounds.west) * width,
    y: (bounds.north - point.latitude) / (bounds.north - bounds.south) * height,
  };
}

export function HomeScreen() {
  const [initialMap, setInitialMap] = useState<SavedMapLocation>();
  useEffect(() => {
    let active = true;
    void loadLastMap().then((stored) => { if (active) setInitialMap(stored ?? DEFAULT_MAP); });
    return () => { active = false; };
  }, []);
  return initialMap ? <RiderHome initialMap={initialMap} /> : null;
}

function RiderHome({ initialMap }: { initialMap: SavedMapLocation }) {
  const [initialCamera] = useState(() => ({ ...initialMap.location, zoomLevel: INITIAL_ZOOM }));
  const [center, setCenter] = useState(initialMap.location);
  const [browse, setBrowse] = useState({ coordinate: initialMap.location, label: initialMap.label, fromDevice: false });
  const [bounds, setBounds] = useState<MapBounds>();
  const [zoom, setZoom] = useState(INITIAL_ZOOM);
  const [focus, setFocus] = useState<Coordinates>();
  const [selected, setSelected] = useState<Station>();
  const [labelVisible, setLabelVisible] = useState(false);
  const [mapPick, setMapPickState] = useState<RouteEndpointRole>();
  const [activeEndpoint, setActiveEndpoint] = useState<RouteEndpointRole>('origin');
  const [endpointDrafts, setEndpointDrafts] = useState<Partial<Record<RouteEndpointRole, string>>>({});
  const [view, setView] = useState<ViewMode>('nearby');
  const [sheet, setSheet] = useState<SheetLevel>('mid');
  const [dragHeight, setDragHeight] = useState<number>();
  const [{ width: workspaceWidth, height: workspaceHeight }, setWorkspaceSize] = useState({ width: 360, height: 552 });
  const [zoomRequest, setZoomRequest] = useState<{ change: number } | { position: Coordinates; zoom: number }>();
  const [showPrivacy, setShowPrivacy] = useState(false);
  const [now, setNow] = useState(Date.now);
  const [dark, setDark] = useState(() => new Date().getHours() >= 19 || new Date().getHours() < 7);
  const [radius, setRadius] = useState(500);
  const [nearbyLimit, setNearbyLimit] = useState<number | null>(5);
  const [filter, setFilter] = useState(false);
  const [forecastMinutes, setForecastMinutes] = useState(0);
  const [searchText, setSearchText] = useState('');
  const [submittedSearch, setSubmittedSearch] = useState('');
  const [showSearch, setShowSearch] = useState(false);
  const [selectedPlace, setSelectedPlace] = useState<Place>();
  const [routeIndex, setRouteIndex] = useState(0);
  const [notice, setNotice] = useState<string>();
  const handledLocationLoss = useRef<LocationLoss | undefined>(undefined);
  const savedTheme = useRef(false);
  const sheetScroll = useRef<ScrollView>(null);
  const shownRoute = useRef<number | undefined>(undefined);
  const map = useRef<RiderMapHandle>(null);
  const mapPickRevision = useRef(0);
  const publishViewport = useCallback((next: MapBounds) => {
    setBounds(next);
    setLabelVisible(true);
  }, []);

  const theme = riderThemes[dark ? 'dark' : 'light'];
  const planner = useRoutePlanner();
  const location = useCurrentLocation();
  const saved = useSavedItems();
  const mode = useQuery({
    queryKey: ['mode'],
    queryFn: ({ signal }) => fetchServiceMode(signal),
    staleTime: Infinity,
  });
  const sample = mode.data === 'sample';
  const modeUnknown = mode.data === undefined;
  const showSaved = view === 'favorites';
  const nearbyCenter = browse.coordinate;
  const nearby = useNearbyStations(nearbyCenter, radius, 100);
  // Native SDK zoom increases as the camera moves closer. Web Kakao uses the opposite scale.
  const webLevel = 19 - zoom;
  const clusterZoom = !mapPick && webLevel >= CLUSTER_MIN_LEVEL ? clusterLevel(webLevel) : undefined;
  const viewport = useViewportStations(bounds, clusterZoom === undefined);
  const clusters = useStationClusters(bounds, clusterZoom);
  const mapHeight = workspaceHeight - PEEK_SHEET_HEIGHT;
  const clusterMarkers = useMemo(() => {
    if (!bounds || !clusters.data) return EMPTY_CLUSTERS;
    const projected = clusters.data.map((item) => ({
      ...item,
      ...projectPoint(item, bounds, workspaceWidth, mapHeight),
    })).filter(({ x, y }) => x >= -30 && x <= workspaceWidth + 30 && y >= 20 && y <= mapHeight - 40);
    return mergeOverlappingClusters(projected).map(({ id, latitude, longitude, stationCount, parkingBikeTotCnt }): MapMarker => ({
      id: `cluster:${id}`,
      coordinate: { latitude, longitude },
      text: String(stationCount),
      subtext: `${parkingBikeTotCnt}대`,
      kind: 'cluster',
    }));
  }, [bounds, clusters.data, workspaceWidth, mapHeight]);
  const favoriteIds = saved.saved?.favorites.map(({ stationId }) => stationId) ?? [];
  const favorites = useBatchStations(favoriteIds, nearbyCenter);
  const check = useMutation({ mutationFn: requestRouteMappingCheck });
  const search = useSearchSuggestions(view === 'route' ? '' : submittedSearch || (showSearch ? searchText : ''),
    nearbyCenter, Boolean(submittedSearch));
  const subwayCenter = location.point ?? nearbyCenter;
  const subway = useQuery({
    queryKey: ['nearby-subways', subwayCenter],
    queryFn: ({ signal }) => fetchNearbySubways(subwayCenter, signal),
    enabled: view !== 'route', retry: false, gcTime: 0, refetchOnWindowFocus: false,
  });

  useEffect(() => {
    let active = true;
    void AsyncStorage.getItem(RIDER_THEME_KEY).then((value) => {
      if (active && !savedTheme.current && (value === 'light' || value === 'dark')) setDark(value === 'dark');
    }).catch(() => {});
    return () => { active = false; };
  }, []);

  useEffect(() => {
    const update = () => setNow(Date.now());
    const timer = setInterval(update, STATION_REFRESH_MS);
    const subscription = AppState.addEventListener('change', (state) => { if (state === 'active') update(); });
    return () => { clearInterval(timer); subscription.remove(); };
  }, []);

  useEffect(() => {
    if (!location.locationLoss || handledLocationLoss.current === location.locationLoss) return;
    handledLocationLoss.current = location.locationLoss;
    if (location.point) return;
    setBrowse((previous) => previous.fromDevice ? { coordinate: center, label: '지도 중심 주변', fromDevice: false } : previous);
    if (location.locationLoss.reason === 'expired') planner.freezeDeviceEndpoints();
    else planner.clearDeviceEndpoints();
  }, [location.locationLoss, location.point, center, planner]);

  const smokeRoute = check.data;
  const candidateRoutes = useMemo(() => planner.route ? [planner.route.route, ...planner.route.alternatives] : [], [planner.route]);
  const routeResult = view === 'route' ? candidateRoutes[routeIndex] ?? candidateRoutes[0] : undefined;
  const routeData = routeResult ?? (view === 'route' ? smokeRoute : undefined);
  const routeBaseStations = useMemo(() => routeData ? [routeData.departureStation, routeData.arrivalStation] : undefined, [routeData]);
  const routeStationIds = routeBaseStations?.map(({ stationId }) => stationId) ?? [];
  const routeInventory = useBatchStations(routeStationIds, routeResult?.origin ?? INITIAL_LOCATION);
  const routeReceivedAt = planner.route?.receivedAt ?? smokeRoute?.receivedAt ?? 0;
  const routeObservations = routeInventory.dataUpdatedAt >= routeReceivedAt ? routeInventory.data : undefined;
  const routeStations = useMemo(() => routeBaseStations?.map((station) => routeStationObservation(station, routeObservations)),
    [routeBaseStations, routeObservations]);
  const selectedRouteStation = routeStations?.find(({ stationId }) => stationId === selected?.stationId);
  const detail = useStationDetail(selectedRouteStation ? undefined : selected?.stationId);
  const segments = useMemo(() => routeResult ? routeResult.legs.map(({ mode, path }): MapRouteSegment => ({
    type: mode === 'bicycle' ? 'BIKE' : 'WALK', path,
  })) : view === 'route' ? smokeRoute?.segments : undefined, [routeResult, smokeRoute, view]);
  const fitCoordinates = useMemo(() => segments?.flatMap(({ path }) => path), [segments]);
  const routeNearby = useNearbyStations(planner.endpoints[activeEndpoint]?.coordinate ?? center, 1000, 100, view === 'route' && !routeResult);

  const nearbyStations = nearby.data ?? EMPTY_STATIONS;
  const viewStations = viewport.data?.stations ?? (bounds ? EMPTY_STATIONS : nearbyStations);
  const listQuery = showSaved ? favorites : nearby;
  const matches = useMemo(() => {
    if (!submittedSearch || search.pending) return;
    const filtered = matchSearchResults(search.places.data ?? [], search.stations.data ?? [], submittedSearch);
    const score = (place: Place) => bestSearchScore(place.name, place.address, submittedSearch);
    return { stations: filtered.stations, places: filtered.places.sort((a, b) => score(a) - score(b)) };
  }, [submittedSearch, search.pending, search.places.data, search.stations.data]);
  const baseList = submittedSearch ? matches?.stations ?? EMPTY_STATIONS : listQuery.data ?? EMPTY_STATIONS;
  const outlook = useStationListForecasts(baseList, forecastMinutes, { enabled: view !== 'route' && !sample && !modeUnknown, now });
  const sortedList = baseList.filter((station) => !filter || ['available', 'low'].includes(
    stationOutlook(station, outlook.forecastMap.get(station.stationId), outlook.effectiveMinutes, now).state))
    .sort((a, b) => compareOutlooks(a, b, outlook.forecastMap, outlook.effectiveMinutes, now));
  const listStations = !submittedSearch && !showSaved && nearbyLimit !== null ? sortedList.slice(0, nearbyLimit) : sortedList;
  const currentSelection = selectedRouteStation ?? (selected && {
    ...(viewStations.find(({ stationId }) => stationId === selected.stationId)
      ?? listStations.find(({ stationId }) => stationId === selected.stationId) ?? selected),
    ...detail.data,
  });
  const selectedId = currentSelection?.stationId;
  const selectedCoordinate = currentSelection ? stationLocation(currentSelection) : null;
  const selectedLabelPoint = selectedCoordinate && bounds && isSupportedViewport(bounds)
    ? projectPoint(selectedCoordinate, bounds, workspaceWidth, mapHeight) : undefined;
  const browseLabel = browse.label === initialMap.label && browse.coordinate === initialMap.location
    ? displaySavedMapLabel(initialMap, [...nearbyStations, ...viewStations]) : browse.label;
  const heights = sheetHeights(workspaceHeight, Boolean(selectedId));
  const sheetHeight = dragHeight ?? heights[sheet];
  const routeFitsMap = Boolean(!mapPick && routeData && !selectedId);
  const mapBottomInset = routeFitsMap ? heights.mid - PEEK_SHEET_HEIGHT + 24 : 0;

  useEffect(() => {
    sheetScroll.current?.scrollTo({ y: 0, animated: false });
  }, [selectedId, submittedSearch, view]);

  useEffect(() => {
    if (routeData) {
      setSheet('mid');
      setFocus(undefined);
    }
  }, [routeData]);

  const marks = useMemo((): MapMarker[] => {
    const routeMode = view === 'route';
    const waitingForRoute = routeMode && planner.pending;
    const markers = routeStations || waitingForRoute ? [] : [...clusterMarkers];
    const { origin, destination } = planner.endpoints;
    const pinned = new Map<string, Station>();
    if (waitingForRoute) {
      [origin?.station, destination?.station].forEach((station) => {
        if (station) pinned.set(station.stationId, station);
      });
      viewStations.forEach((station) => {
        if (pinned.has(station.stationId)) pinned.set(station.stationId, station);
      });
    }
    const listed = routeStations ?? (waitingForRoute ? [...pinned.values()] : viewStations);
    const baseMarkers = currentSelection && !listed.some((station) => station.stationId === currentSelection.stationId)
      ? [...listed, currentSelection] : listed;
    const offsets = new Map<string, { x: number; y: number }>();
    if (bounds && isSupportedViewport(bounds) && !routeStations && !waitingForRoute) {
      const projected = baseMarkers.flatMap((station) => {
        const coordinate = stationLocation(station);
        return coordinate ? [{
          id: station.stationId,
          stationIds: [station.stationId],
          ...projectPoint(coordinate, bounds, workspaceWidth, mapHeight),
        }] : [];
      });
      separateMarkerGroups(projected, selectedId ?? null).forEach(({ id, offsetX, offsetY }) => {
        if (offsetX || offsetY) offsets.set(id, { x: offsetX ?? 0, y: offsetY ?? 0 });
      });
    }
    baseMarkers.forEach((station, index) => {
      const coordinate = stationLocation(station);
      if (!coordinate) return;
      const state = availability(station, now);
      const role = index === 0 ? 'rental' : 'return';
      markers.push({
        id: `station:${station.stationId}`,
        coordinate,
        text: state === 'unknown' ? '?' : `${station.parkingBikeTotCnt}`,
        kind: routeStations ? role : station.stationId === selectedId ? 'selected' : 'station',
        stock: state === 'available' ? 'ready' : state,
        highlighted: station.stationId === selectedId,
        offset: offsets.get(station.stationId),
      });
    });
    if (location.point) markers.push({ id: 'location', coordinate: location.point, text: '', kind: 'location' });
    if (selectedPlace && !routeMode) markers.push({ id: 'place', coordinate: selectedPlace.location, text: selectedPlace.name, kind: 'place' });
    if (!routeMode || smokeRoute) return markers;
    if (origin && !origin.fromDevice && !pinned.has(origin.stationId ?? '') && (!routeStations || !origin.stationId)) {
      markers.push({ id: 'origin', coordinate: origin.coordinate, text: '출발', kind: 'origin' });
    }
    if (destination && !destination.fromDevice && !pinned.has(destination.stationId ?? '') && (!routeStations || !destination.stationId)) {
      markers.push({ id: 'destination', coordinate: destination.coordinate, text: '목적지', kind: 'destination' });
    }
    return markers;
  }, [routeStations, viewStations, clusterMarkers, currentSelection, selectedId, bounds, workspaceWidth, mapHeight, now,
    planner.endpoints, planner.pending, smokeRoute, location.point, view, selectedPlace]);

  function toggleTheme() {
    const next = !dark;
    savedTheme.current = true;
    setDark(next);
    void AsyncStorage.setItem(RIDER_THEME_KEY, next ? 'dark' : 'light').catch(() => {});
  }

  function setMapPick(role?: RouteEndpointRole) {
    mapPickRevision.current += 1;
    setMapPickState(role);
  }

  function switchView(next: ViewMode) {
    Keyboard.dismiss();
    location.cancel();
    setView(next);
    setSelected(undefined);
    setMapPick(undefined);
    setSelectedPlace(undefined);
    setSubmittedSearch('');
    setSearchText('');
    setShowSearch(false);
    setNotice(undefined);
    setSheet(next === 'route' && !planner.route && !smokeRoute ? 'full' : 'mid');
    if (next === 'route' && location.point && !planner.endpoints.origin) {
      planner.setEndpoint('origin', { name: '현재 위치', coordinate: location.point, fromDevice: true });
    }
  }

  function selectStation(station: Station) {
    Keyboard.dismiss();
    location.cancel();
    setShowSearch(false);
    setSelectedPlace(undefined);
    setSelected(station);
    if (submittedSearch && view !== 'route') void saved.addRecent(submittedSearch);
    setLabelVisible(false);
    setSheet('mid');
    const coordinate = stationLocation(station);
    if (coordinate) {
      saveLastMap(coordinate, `${displayStationName(station.stationName)} 주변`);
      setFocus({ ...coordinate });
    }
  }

  function choose(role: RouteEndpointRole, endpoint: Endpoint, moveMap = true) {
    Keyboard.dismiss();
    location.cancel();
    check.reset();
    planner.setEndpoint(role, endpoint);
    setEndpointDrafts((previous) => ({ ...previous, [role]: undefined }));
    setActiveEndpoint(role);
    setMapPick(undefined);
    setSelected(undefined);
    setNotice(undefined);
    setView('route');
    setSheet('full');
    setRouteIndex(0);
    if (moveMap) setFocus({ ...endpoint.coordinate });
  }

  function chooseStation(role: RouteEndpointRole, station: Station) {
    const coordinate = stationLocation(station);
    if (!coordinate) return;
    const displayName = displayStationName(station.stationName);
    if (view === 'route' && !selectedId) saveLastMap(coordinate, `${displayName} 주변`);
    choose(role, { name: station.stationName, displayName, address: station.address ?? '', coordinate, stationId: station.stationId, station });
  }

  function chooseRouteSearch(role: RouteEndpointRole, result: SearchSuggestion, term: string) {
    if (result.kind === 'station') {
      const coordinate = stationLocation(result.station);
      if (!coordinate) return;
      const displayName = displayStationName(result.station.stationName);
      saveLastMap(coordinate, `${displayName} 주변`);
      choose(role, { name: result.station.stationName, displayName, address: result.station.address ?? '', coordinate });
    } else {
      saveLastMap(result.place.location, `${result.name} 주변`);
      choose(role, { name: result.name, address: result.address, coordinate: result.place.location });
    }
    void saved.addRecent(term);
  }

  function openPlace(place: Place, term?: string) {
    Keyboard.dismiss();
    location.cancel();
    setShowSearch(false);
    setSubmittedSearch('');
    setSearchText(place.name);
    setSelected(undefined);
    setSelectedPlace(place);
    saveLastMap(place.location, `${place.name} 주변`);
    setBrowse({ coordinate: place.location, label: `${place.name} 주변`, fromDevice: false });
    setRadius(500);
    setNearbyLimit(null);
    setView('nearby');
    setSheet('mid');
    setFocus({ ...place.location });
    if (term) void saved.addRecent(term);
  }

  function submitSearch(term = searchText.trim()) {
    if (term.length < 2) {
      setNotice('장소나 대여소 이름을 두 글자 이상 입력해 주세요.');
      return;
    }
    Keyboard.dismiss();
    location.cancel();
    setSearchText(term);
    setSubmittedSearch(term);
    setShowSearch(false);
    setSelected(undefined);
    setSelectedPlace(undefined);
    setFocus(undefined);
    setNotice(undefined);
    setSheet('full');
  }

  function useDeviceLocation(role?: RouteEndpointRole) {
    Keyboard.dismiss();
    setShowSearch(false);
    setMapPick(undefined);
    void location.locate((coordinate) => {
      if (!role) saveLastMap(coordinate, '마지막으로 확인한 위치 주변');
      setBrowse({ coordinate, label: '내 위치 주변', fromDevice: true });
      setRadius(500);
      setNearbyLimit(5);
      if (role) {
        check.reset();
        planner.setEndpoint(role, { name: '현재 위치', coordinate, fromDevice: true });
        setEndpointDrafts((previous) => ({ ...previous, [role]: undefined }));
        setActiveEndpoint(role);
        setSheet('full');
      }
      setSelected(undefined);
      setSelectedPlace(undefined);
      setFocus(coordinate);
    });
  }

  function chooseMapPosition(coordinate: Coordinates) {
    if (!mapPick) {
      setShowSearch(false);
      setSelectedPlace(undefined);
      Keyboard.dismiss();
      return;
    }
    const role = mapPick;
    choose(role, { name: role === 'origin' ? '지도에서 지정한 출발지' : '지도에서 지정한 목적지', coordinate }, false);
    void reversePlace(coordinate).then((place) => {
      if (place) planner.labelEndpoint(role, coordinate, place.name, place.address);
    }).catch(() => {});
  }

  async function confirmMapCenter() {
    const revision = ++mapPickRevision.current;
    try {
      const coordinate = await map.current?.readCenter();
      if (coordinate && mapPickRevision.current === revision) chooseMapPosition(coordinate);
    } catch {
      if (mapPickRevision.current === revision) setNotice('지도 중앙 위치를 확인하지 못했어요. 지도를 잠시 멈추고 다시 시도해 주세요.');
    }
  }

  function refreshStations() {
    if (submittedSearch) {
      void search.places.refetch();
      void search.stations.refetch();
      return;
    }
    void nearby.refetch();
    if (bounds && isSupportedViewport(bounds)) {
      if (clusterZoom === undefined) void viewport.refetch();
      else void clusters.refetch();
    }
    if (selectedId && !selectedRouteStation) void detail.refetch();
    if (favoriteIds.length) void favorites.refetch();
    if (routeStationIds.length) void routeInventory.refetch();
  }

  function runRouteSearch() {
    Keyboard.dismiss();
    location.cancel();
    check.reset();
    setMapPick(undefined);
    setSelected(undefined);
    setFocus(undefined);
    setRouteIndex(0);
    void planner.search();
  }

  const missingFavorites = showSaved && favorites.isSuccess && !submittedSearch
    ? favoriteIds.filter((id) => !favorites.data.some((station) => station.stationId === id)) : [];
  const searchFailed = Boolean(submittedSearch && search.places.isError && search.stations.isError);
  const emptyList = !listStations.length && (submittedSearch ? !search.pending && !searchFailed
    : (showSaved && !favoriteIds.length) || (!listQuery.isFetching && !listQuery.isError));
  const subwayPlaces = (subway.data ?? []).map((place) => ({ ...place, name: place.name.replace(/역(?:\s|[0-9]+호선).*$/, '역') }));
  const subwayMessage = subway.isError ? '주변역 조회 불가' : subway.isFetching && !subway.data ? '찾는 중…'
    : subway.data?.length === 0 ? '2km 안에 지하철역이 없어요' : undefined;
  const routeEndpoint = planner.endpoints[activeEndpoint];

  const footer = <View style={s.footer}>
    {saved.error && <Pressable accessibilityRole="button" onPress={() => void saved.reload()}>
      <Text style={{ color: theme.error, fontSize: 11 }}>{saved.error} 다시 읽기</Text>
    </Pressable>}
    {__DEV__ && <Pressable accessibilityRole="button" disabled={check.isPending} onPress={() => {
      planner.clearRoute(); setSelected(undefined); setView('route'); setFocus(undefined); check.mutate();
    }}>
      <Text style={{ color: theme.muted, fontSize: 11 }}>{check.isPending ? '확인 중…' : '개발용 경로 확인'}</Text>
    </Pressable>}
    <Pressable accessibilityRole="button" onPress={() => setShowPrivacy(true)}>
      <Text style={{ color: theme.muted, fontSize: 11 }}>개인정보 처리 안내</Text>
    </Pressable>
  </View>;

  return (
    <RiderTheme.Provider value={theme}>
      <SafeAreaView style={[s.shell, { backgroundColor: theme.surface }]}>
        <StatusBar style={dark ? 'light' : 'dark'} />
        <MapHeader view={view} onView={switchView} favorites={favoriteIds.length} dark={dark} onTheme={toggleTheme} />
        <View style={s.workspace} onLayout={({ nativeEvent: { layout: { width, height } } }) => setWorkspaceSize({ width, height })}>
          <View style={s.map}>
            <RiderMap
              ref={map}
              initialCamera={initialCamera}
              focus={focus}
              zoomRequest={zoomRequest}
              bottomInset={Math.max(0, mapBottomInset)}
              logoInset={mapPick ? 0 : Math.max(0, heights[sheet] - PEEK_SHEET_HEIGHT)}
              fitRoute={routeFitsMap}
              focusVisibleInset={!mapPick && selectedId ? Math.max(0, heights.mid - PEEK_SHEET_HEIGHT) : undefined}
              dark={dark}
              fitCoordinates={fitCoordinates}
              markers={marks}
              segments={segments}
              onViewportChange={publishViewport}
              onZoomChange={setZoom}
              onCameraGesture={() => { location.cancel(); setLabelVisible(false); }}
              onCenterChange={(coordinate, reason) => {
                setCenter(coordinate);
                if (reason !== 'gesture' || Math.hypot(center.latitude - coordinate.latitude, center.longitude - coordinate.longitude) < 0.00002) return;
                saveLastMap(coordinate, '마지막으로 본 지도 주변');
                if (view === 'route' || mapPick) return;
                setSelectedPlace(undefined);
                setNearbyLimit(5);
                setSubmittedSearch('');
                setSearchText('');
                setShowSearch(false);
                setBrowse((previous) => Math.hypot(previous.coordinate.latitude - coordinate.latitude,
                  previous.coordinate.longitude - coordinate.longitude) < 0.0002
                  ? previous : { coordinate, label: '지도 중심 주변', fromDevice: false });
              }}
              onMapPress={chooseMapPosition}
              onMarkerPress={(id) => {
                const cluster = clusterMarkers.find((marker) => marker.id === id);
                if (cluster) {
                  location.cancel();
                  if (view !== 'route') {
                    setSelectedPlace(undefined);
                    saveLastMap(cluster.coordinate, '마지막으로 본 지도 주변');
                    setBrowse({ coordinate: cluster.coordinate, label: '지도 중심 주변', fromDevice: false });
                  }
                  setZoomRequest({ position: cluster.coordinate, zoom: 19 - clusterZoomLevel(webLevel) });
                  return;
                }
                const station = (routeStations ?? viewStations).find(({ stationId }) => `station:${stationId}` === id)
                  ?? [selected, planner.endpoints.origin?.station, planner.endpoints.destination?.station]
                    .find((item) => item && `station:${item.stationId}` === id);
                if (station) {
                  if (mapPick) {
                    const coordinate = stationLocation(station);
                    if (coordinate) chooseMapPosition(coordinate);
                  } else selectStation(station);
                }
              }}
            />
          </View>
          {view !== 'route' && !mapPick && <View style={s.search} pointerEvents="box-none">
            <SearchField text={searchText} label="장소, 주소 또는 대여소 검색"
              onChange={(value) => { location.cancel(); setSearchText(value); setSubmittedSearch(''); setSelectedPlace(undefined); setShowSearch(true); }}
              onFocus={() => { location.cancel(); setShowSearch(true); if (sheet === 'full') setSheet('mid'); }}
              onClear={() => { setSearchText(''); setSubmittedSearch(''); setSelectedPlace(undefined); }}
              onSubmit={() => submitSearch()} />
            {showSearch && !submittedSearch && searchText.trim().length >= 2 && <SearchResults query={search} floating
              onChoose={(result) => {
                if (result.kind === 'place') openPlace(result.place, searchText.trim());
                else {
                  void saved.addRecent(searchText.trim());
                  const displayName = displayStationName(result.name);
                  setBrowse({ coordinate: stationLocation(result.station) ?? center,
                    label: `${displayName} 주변`, fromDevice: false });
                  setRadius(500);
                  setNearbyLimit(null);
                  setSearchText(displayName);
                  selectStation(result.station);
                }
              }} />}
          </View>}
          {(notice || location.message) && !mapPick && <Pressable accessibilityRole="button" accessibilityLiveRegion="polite"
            onPress={() => { setNotice(undefined); location.cancel(); }}
            style={[s.toast, { backgroundColor: theme.surface, bottom: sheetHeight + 12, borderColor: theme.border }]}>
            <Text style={{ color: theme.text, fontSize: 11, flex: 1 }}>{notice || location.message}</Text>
            <RiderIcon name="close" size={14} color={theme.quiet} />
          </Pressable>}
          {currentSelection && labelVisible && sheet !== 'full' && selectedLabelPoint &&
            selectedLabelPoint.x >= 0 && selectedLabelPoint.x <= workspaceWidth &&
            selectedLabelPoint.y >= 0 && selectedLabelPoint.y + 17 < workspaceHeight - sheetHeight && <View pointerEvents="none"
            style={[s.selectedLabelRow, { top: selectedLabelPoint.y + 14 }]}>
            <View style={[s.selectedPlaceLabel, { backgroundColor: theme.surface,
              transform: [{ translateX: selectedLabelPoint.x - workspaceWidth / 2 }] }]}>
              <Text numberOfLines={1} style={{ color: theme.text, fontSize: 10, fontWeight: '600' }}>
                {displayStationName(currentSelection.stationName)}
              </Text>
            </View>
          </View>}
          {sheet !== 'full' && !mapPick && <MapButtons bottom={sheetHeight + 16} zoom={(change) => setZoomRequest({ change })}
            locate={() => useDeviceLocation()} locating={location.pending} />}
          {mapPick && <>
            <View style={[s.crosshair, { top: (workspaceHeight - PEEK_SHEET_HEIGHT) / 2 }]} pointerEvents="none">
              <View style={s.crosshairContents}>
                <View style={[s.crosshairLabel, { backgroundColor: theme.navy }]}>
                  <Text style={{ color: '#FFFFFF', fontWeight: '700', fontSize: 12 }}>{mapPick === 'origin' ? '출발지' : '목적지'}</Text>
                </View>
                <Image accessible={false} resizeMode="contain" style={s.pickerPin}
                  source={dark ? require('../../../assets/web-ui/picker-pin-dark.png') : require('../../../assets/web-ui/picker-pin.png')} />
              </View>
              <View style={[s.crosshairTarget, { borderColor: theme.accent }]} />
            </View>
            <View style={[s.picker, { backgroundColor: theme.surface, borderColor: theme.border }]}>
              <Text style={{ fontSize: 12, color: theme.text }}>지도를 움직여 {mapPick === 'origin' ? '출발지' : '목적지'}를 골라 주세요.</Text>
              {!!notice && <Text style={{ color: theme.error, fontSize: 11 }}>{notice}</Text>}
              <SheetButton label={mapPick === 'origin' ? '이 위치에서 출발' : '이 위치로 도착'} disabled={!bounds} onPress={() => void confirmMapCenter()} />
              <Pressable accessibilityRole="button" onPress={() => { setMapPick(undefined); setSheet('full'); }}>
                <Text style={{ color: theme.accent, fontSize: 12, textAlign: 'center' }}>취소</Text>
              </Pressable>
            </View>
          </>}
          {!mapPick && <BottomSheet level={sheet} heights={heights} dragHeight={dragHeight} onDrag={setDragHeight}
            onLevel={(level) => { if (level !== sheet) Keyboard.dismiss(); setShowSearch(false); setSheet(level); }}>
            <ScrollView key={selectedId ? `station:${selectedId}` : 'main'} ref={sheetScroll} style={s.sheetContent}
              keyboardShouldPersistTaps="handled" nestedScrollEnabled
              showsVerticalScrollIndicator={false}>
              {currentSelection ? <StationPanel station={currentSelection} now={now} sample={sample} modeUnknown={modeUnknown}
                retryMode={mode.isError ? () => { void mode.refetch(); } : undefined}
                saved={favoriteIds.includes(currentSelection.stationId)} onSave={() => void saved.toggleFavorite(currentSelection.stationId)}
                onClose={() => { setSelected(undefined); setFocus(undefined); }} onEndpoint={(role) => chooseStation(role, currentSelection)}
                detailNotice={detail.isError ? '대여소 상세를 갱신하지 못했어요.' : undefined} />
                : view === 'route' ? <>
                  <RoutePanel
                    center={center}
                    active={activeEndpoint}
                    onActive={(role) => { if (role !== activeEndpoint) location.cancel(); setActiveEndpoint(role); }}
                    endpoints={planner.endpoints}
                    drafts={endpointDrafts}
                    onDraft={(role, text) => {
                      location.cancel();
                      setEndpointDrafts((previous) => ({ ...previous, [role]: text }));
                      if (planner.endpoints[role]) planner.setEndpoint(role);
                    }}
                    onChoose={chooseRouteSearch}
                    onUnpin={(role) => {
                      const endpoint = planner.endpoints[role];
                      if (endpoint) planner.setEndpoint(role, { ...endpoint, stationId: undefined, station: undefined });
                    }}
                    favorites={favorites.data ?? EMPTY_STATIONS}
                    favoritesLoading={favorites.isFetching}
                    favoritesError={favorites.isError}
                    nearby={[...(routeNearby.data ?? [])].sort((a, b) => compareStations(a, b, now)).slice(0, 3)}
                    nearbyLoading={routeNearby.isPending}
                    nearbyError={routeNearby.isError}
                    onRetryNearby={() => void routeNearby.refetch()}
                    onChooseStation={chooseStation}
                    onInspectStation={selectStation}
                    onLocate={useDeviceLocation}
                    onPick={(role) => { Keyboard.dismiss(); location.cancel(); setMapPick(role); setSheet('peek'); }}
                    onSubmit={runRouteSearch}
                    busy={planner.pending}
                    locating={location.pending}
                    locationAccuracy={routeEndpoint?.fromDevice && routeEndpoint.coordinate === location.point ? location.accuracy : undefined}
                    configured={planner.configured}
                    error={planner.error || (check.isError ? describeApiError(check.error) : undefined)}
                    route={routeResult}
                    routes={candidateRoutes}
                    routeIndex={routeIndex}
                    onRouteIndex={setRouteIndex}
                    routeStations={routeStations}
                    partial={planner.route?.partial}
                    inventoryError={routeInventory.isError}
                    onResultLayout={(position) => {
                      if (!planner.route || shownRoute.current === planner.route.receivedAt) return;
                      shownRoute.current = planner.route.receivedAt;
                      requestAnimationFrame(() => sheetScroll.current?.scrollTo({ y: position, animated: true }));
                    }}
                  />
                  {smokeRoute && <View style={s.smoke}><Text style={{ color: theme.text, fontSize: 12 }}>
                    도보 → 자전거 → 도보 · {distanceLabel(smokeRoute.distanceMeters)} · 약 {Math.ceil(smokeRoute.durationSeconds / 60)}분
                  </Text><Pressable accessibilityRole="button" onPress={() => check.reset()}><Text style={{ color: theme.accent }}>개발용 경로 닫기</Text></Pressable></View>}
                  <View style={s.routeFooter}>{footer}</View>
                </> : <ListPanel
                  favorites={showSaved}
                  sample={sample}
                  searched={submittedSearch}
                  title={submittedSearch ? `“${submittedSearch}”` : showSaved ? '저장한 대여소'
                    : browseLabel}
                  rows={listStations}
                  radius={radius}
                  changeRadius={(value) => { setRadius(value); setNearbyLimit(null); }}
                  nearbyLimit={nearbyLimit}
                  filter={filter}
                  changeFilter={() => setFilter((active) => !active)}
                  forecast={{ ...outlook, minutes: forecastMinutes, change: setForecastMinutes }}
                  skeleton={!submittedSearch && listQuery.isPending && (!showSaved || favoriteIds.length > 0)}
                  refresh={refreshStations}
                  now={now}
                  favoriteIds={favoriteIds}
                  missingFavorites={missingFavorites}
                  select={selectStation}
                  toggleFavorite={(id) => void saved.toggleFavorite(id)}
                  subway={subwayPlaces}
                  subwayMessage={subwayMessage}
                  chooseSubway={(place) => openPlace(place)}
                  reliableLocation={Boolean(location.point)}
                  recent={saved.saved?.recent ?? []}
                  onRecent={submitSearch}
                  clearRecent={() => void saved.clearRecent()}
                  selectedPlace={selectedPlace}
                  choosePlaceEndpoint={(role) => {
                    if (selectedPlace) choose(role, { name: selectedPlace.name, address: selectedPlace.address, coordinate: selectedPlace.location });
                  }}
                  empty={emptyList}
                  moreLabel={!showSaved && !submittedSearch && nearbyLimit !== null && sortedList.length > nearbyLimit
                    ? `반경 ${distanceLabel(radius)} 안의 ${sortedList.length}곳 모두 보기` : undefined}
                  showMore={() => setNearbyLimit(null)}
                  notice={<View style={s.notes}>
                    {!submittedSearch && <>
                      {!!bounds && !isSupportedViewport(bounds) && <Text style={{ color: theme.muted, fontSize: 11 }}>지도를 확대하면 대여소를 볼 수 있어요.</Text>}
                      {viewport.data?.truncated && <Text style={{ color: theme.muted, fontSize: 11 }}>화면 중앙에서 가까운 대여소를 표시했어요.</Text>}
                      {viewport.isError && <Text style={{ color: theme.error, fontSize: 11 }}>지도 대여소를 조회하지 못했어요. {describeApiError(viewport.error)}</Text>}
                      {clusters.isError && <Text style={{ color: theme.error, fontSize: 11 }}>지도 대여소 묶음을 조회하지 못했어요. {describeApiError(clusters.error)}</Text>}
                      {listQuery.isError && <Text accessibilityLiveRegion="polite" style={[s.subtleNotice, { color: theme.warning }]}>{describeApiError(listQuery.error)}</Text>}
                    </>}
                    {!!submittedSearch && search.pending && <Text accessibilityLiveRegion="polite" style={[s.searchLoading, { color: theme.text }]}>검색하고 있어요…</Text>}
                    {searchFailed && <View style={s.searchFailure}>
                      <RiderIcon name="search" size={30} color={theme.muted} />
                      <Text accessibilityLiveRegion="polite" style={[s.searchFailureTitle, { color: theme.muted }]}>검색에 연결하지 못했어요</Text>
                      <Pressable accessibilityRole="button" onPress={refreshStations} style={s.retrySearch}>
                        <Text style={{ color: theme.accent, fontSize: 12 }}>다시 검색</Text>
                      </Pressable>
                    </View>}
                    {matches?.places.map((place, index) => <SubmittedPlaceRow key={`${place.name}:${index}`} place={place} term={submittedSearch}
                      onPress={() => openPlace(place, submittedSearch)} />)}
                    {!!submittedSearch && !search.pending && !searchFailed && (search.places.isError || search.stations.isError) &&
                      <Text accessibilityLiveRegion="polite" style={[s.subtleNotice, { color: theme.warning }]}>
                        {search.places.isError ? '장소 검색 연결을 확인하지 못했어요. 대여소 이름으로도 검색할 수 있어요.'
                          : '대여소 검색 연결을 확인하지 못했어요. 장소 검색 결과는 계속 이용할 수 있어요.'}
                      </Text>}
                  </View>}
                  footer={footer}
                />}
            </ScrollView>
          </BottomSheet>}
        </View>
        {showPrivacy && <PrivacyNotice onClose={() => setShowPrivacy(false)} />}
      </SafeAreaView>
    </RiderTheme.Provider>
  );
}

const s = StyleSheet.create({
  shell: { flex: 1 },
  workspace: { flex: 1, overflow: 'hidden' },
  map: { position: 'absolute', top: 0, bottom: PEEK_SHEET_HEIGHT, left: 0, right: 0 },
  search: { position: 'absolute', top: 14, left: 16, right: 16, zIndex: 3 },
  sheetContent: { flex: 1 },
  selectedLabelRow: { position: 'absolute', left: 0, right: 0, alignItems: 'center', zIndex: 2 },
  selectedPlaceLabel: { maxWidth: '82%', borderRadius: 5, paddingVertical: 5, paddingHorizontal: 9 },
  crosshair: { position: 'absolute', alignSelf: 'center', width: 104, height: 0, alignItems: 'center', zIndex: 2 },
  crosshairContents: { position: 'absolute', bottom: 0, width: '100%', alignItems: 'center' },
  pickerPin: { width: 34, height: 45 },
  crosshairTarget: { position: 'absolute', top: -8, width: 16, height: 16, borderRadius: 8, borderWidth: 2, backgroundColor: '#FFFFFF66' },
  crosshairLabel: { borderRadius: 15, paddingVertical: 7, paddingHorizontal: 10, marginBottom: 4 },
  picker: { position: 'absolute', left: 12, right: 12, bottom: 16, borderRadius: 16, padding: 14, gap: 8, borderWidth: 1, zIndex: 3 },
  toast: { position: 'absolute', left: 16, right: 72, padding: 10, borderRadius: 8, borderWidth: 1, flexDirection: 'row', alignItems: 'center', gap: 5, zIndex: 3 },
  footer: { paddingVertical: 12, gap: 12 },
  routeFooter: { paddingHorizontal: 22, paddingBottom: 24 },
  notes: { gap: 5, marginBottom: 5 },
  subtleNotice: { fontSize: 11, lineHeight: 18.7, paddingVertical: 12 },
  searchLoading: { fontSize: 12, padding: 30, textAlign: 'center' },
  searchFailure: { alignItems: 'center', paddingVertical: 35, paddingHorizontal: 6 },
  searchFailureTitle: { fontSize: 13, fontWeight: '500', marginTop: 16, marginBottom: 9 },
  retrySearch: { padding: 10 },
  smoke: { padding: 15, gap: 6 },
});
