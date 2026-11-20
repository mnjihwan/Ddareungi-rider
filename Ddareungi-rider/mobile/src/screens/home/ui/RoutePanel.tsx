import { useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Station } from '../../../entities/station/model/types';
import type { Endpoint } from '../../../features/route-planning/model/endpoints';
import { useSearchSuggestions } from '../../../features/station-search/model/useSearchSuggestions';
import type { SearchSuggestion } from '../../../shared/rider-contract/searchSuggestions';
import { displayStationName } from '../../../shared/rider-contract/stationName';
import { availability, AVAILABILITY_LABEL, distanceLabel, observationLabel } from '../../../shared/rider-contract/status';
import type { BikeRoute, Coordinates, RouteEndpointRole, RouteStep } from '../../../shared/rider-contract/types';
import { RiderIcon } from './MapChrome';
import { SearchField, SearchResults } from './MapSearch';
import { SheetButton } from './StationPanels';
import { riderThemes, useRiderTheme } from './theme';

const ROLES = ['origin', 'destination'] as const;
const LABELS = { origin: '출발지', destination: '목적지' };
const SELECTED_BORDER = '#8DA7CE';
const minutes = (seconds: number) => `${Math.max(0, Math.ceil(seconds / 60))}분`;

function endpointTitle(endpoint?: Endpoint): string | undefined {
  return endpoint?.displayName ?? (endpoint?.stationId ? displayStationName(endpoint.name) : endpoint?.name);
}

interface RoutePanelProps {
  center: Coordinates;
  active: RouteEndpointRole;
  onActive: (role: RouteEndpointRole) => void;
  endpoints: Partial<Record<RouteEndpointRole, Endpoint>>;
  drafts: Partial<Record<RouteEndpointRole, string>>;
  onDraft: (role: RouteEndpointRole, text: string) => void;
  onChoose: (role: RouteEndpointRole, result: SearchSuggestion, term: string) => void;
  onUnpin: (role: RouteEndpointRole) => void;
  favorites: Station[];
  favoritesLoading: boolean;
  favoritesError: boolean;
  nearby: Station[];
  nearbyLoading?: boolean;
  nearbyError?: boolean;
  onRetryNearby?: () => void;
  onChooseStation: (role: RouteEndpointRole, station: Station) => void;
  onInspectStation: (station: Station) => void;
  onLocate: (role: RouteEndpointRole) => void;
  onPick: (role: RouteEndpointRole) => void;
  onSubmit: () => void;
  busy: boolean;
  locating: boolean;
  locationAccuracy?: number;
  configured?: boolean;
  error?: string;
  route?: BikeRoute;
  routes: BikeRoute[];
  routeIndex: number;
  onRouteIndex: (index: number) => void;
  routeStations?: Station[];
  partial?: boolean;
  inventoryError?: boolean;
  onResultLayout: (position: number) => void;
}

export function RoutePanel({
  center, active, onActive, endpoints, drafts, onDraft, onChoose, onUnpin, favorites, favoritesLoading, favoritesError,
  nearby, nearbyLoading, nearbyError, onRetryNearby, onChooseStation, onInspectStation, onLocate, onPick, onSubmit,
  busy, locating, locationAccuracy, configured, error, route, routes, routeIndex, onRouteIndex, routeStations, partial,
  inventoryError, onResultLayout,
}: RoutePanelProps) {
  const theme = useRiderTheme();
  const dark = theme === riderThemes.dark;
  const selectedColor = dark ? theme.blue : theme.navy;
  const selectionSurface = dark ? theme.selected : '#EAF0F8';
  const [open, setOpen] = useState<RouteEndpointRole>();
  const draft = open ? drafts[open] ?? endpointTitle(endpoints[open]) ?? '' : '';
  const search = useSearchSuggestions(draft, center);
  const roleLabel = LABELS[active];
  const selectedStationId = endpoints[active]?.stationId;
  const chooseStation = (station: Station) => {
    setOpen(undefined);
    onChooseStation(active, station);
  };

  return (
    <View style={s.panel}>
      <Text style={[s.eyebrow, { color: theme.quiet }]}>도보 → 따릉이 → 도보</Text>
      <Text style={[s.headline, { color: theme.text }]} accessibilityRole="header">목적지까지 따릉이로</Text>
      <Text style={[s.intro, { color: theme.muted }]}>걸어서 대여하고, 목적지 근처에서 반납하세요.</Text>
      {ROLES.map((role) => {
        const endpoint = endpoints[role];
        const title = endpointTitle(endpoint);
        const text = drafts[role] ?? title ?? '';
        const showSuggestions = open === role && draft.trim().length >= 2;
        return (
          <View key={role} style={[s.endpoint, showSuggestions && s.endpointExpanded]}>
            <Text style={[s.label, { color: role === active ? theme.accent : theme.text }]}>{LABELS[role]}</Text>
            <View>
              <SearchField text={text} label={`${LABELS[role]} 장소 또는 주소 검색`} route active={role === active}
                onChange={(value) => { onDraft(role, value); setOpen(role); }}
                onFocus={() => { onActive(role); setOpen(endpoint ? undefined : role); }}
                onClear={() => { onActive(role); onDraft(role, ''); setOpen(undefined); }}
                onSubmit={() => {
                  onActive(role);
                  if (text !== title) onDraft(role, text);
                  setOpen(role);
                }}
              />
              {showSuggestions && <View style={s.suggestions}>
                <SearchResults query={search} onChoose={(result) => { onChoose(role, result, draft.trim()); setOpen(undefined); }} />
              </View>}
            </View>
            {endpoint && <Text style={[s.selected, { color: theme.muted }]}>
              선택됨{endpoint.address ? ` · ${endpoint.address}` : ''}
            </Text>}
          </View>
        );
      })}
      <View style={[s.tools, { borderColor: theme.border, backgroundColor: dark ? theme.raised : '#F7F9FC' }]}>
        <Text accessibilityLiveRegion="polite" style={[s.toolTarget, { color: theme.accent }]}>{roleLabel} 선택 중</Text>
        <View style={s.savedRow}>
          <RiderIcon name="star" size={16} color={theme.muted} />
          {favorites.length ? (
            <ScrollView horizontal keyboardShouldPersistTaps="handled" showsHorizontalScrollIndicator={false}
              contentContainerStyle={s.chips}>
              {favorites.map((station) => {
                const selected = station.stationId === selectedStationId;
                return <Pressable key={station.stationId} accessibilityRole="button" accessibilityState={{ selected }}
                  onPress={() => chooseStation(station)} style={[s.chip, {
                    backgroundColor: selected ? selectionSurface : theme.surface, borderColor: selected ? SELECTED_BORDER : theme.field,
                  }]}>
                  <Text style={[s.chipLabel, { color: selected ? selectedColor : theme.muted }, selected && s.bold]}>
                    {displayStationName(station.stationName)}
                  </Text>
                </Pressable>;
              })}
            </ScrollView>
          ) : <Text style={{ color: theme.muted, fontSize: 12, paddingVertical: 8 }}>
            {favoritesLoading ? '불러오는 중…' : favoritesError ? '즐겨찾기를 불러오지 못했어요' : '저장된 대여소가 없어요'}
          </Text>}
        </View>
        <View style={s.toolsActions}>
          <Pressable style={s.toolAction} accessibilityRole="button" disabled={locating}
            onPress={() => { setOpen(undefined); onLocate(active); }}>
            <RiderIcon name="locate" size={15} color={theme.accent} />
            <Text style={{ color: theme.accent, fontSize: 12 }}>{locating ? '확인 중…' : '현재 위치'}</Text>
          </Pressable>
          <Pressable style={s.toolAction} accessibilityRole="button" onPress={() => { setOpen(undefined); onPick(active); }}>
            <RiderIcon name="pin" size={15} color={theme.accent} /><Text style={{ color: theme.accent, fontSize: 12 }}>지도에서 선택</Text>
          </Pressable>
        </View>
        {locationAccuracy !== undefined && <Text accessibilityLiveRegion="polite" style={[s.statusNotice, s.accuracyNotice, { color: theme.warning }]}>
          위치 정확도 약 {distanceLabel(locationAccuracy)}{locationAccuracy > 100 && ' · 실제 위치와 다르면 장소를 검색해 주세요.'}
        </Text>}
      </View>
      {ROLES.map((role) => {
        const endpoint = endpoints[role];
        if (!endpoint?.stationId) return null;
        return <View key={role} style={[s.pinned, { backgroundColor: dark ? theme.selected : '#EDF3FA' }]}>
          <View style={s.grow}><Text style={{ fontSize: 12, color: theme.text }}>{role === 'origin' ? '출발 대여소' : '도착 대여소'}</Text>
            <Text style={[s.pinnedName, { color: theme.text }]}>{endpointTitle(endpoint)}</Text></View>
          <Pressable accessibilityRole="button" style={s.unpin} onPress={() => onUnpin(role)}>
            <Text style={{ color: theme.accent, fontSize: 11 }}>자동 선택</Text>
          </Pressable>
        </View>;
      })}
      <View style={s.submit}>
        <SheetButton label={busy ? '이동 경로를 비교하고 있어요…' : route ? '수량 확인하고 다시 검색' : '따릉이 경로 찾기'}
          icon={busy ? 'refresh' : 'arrow'} disabled={!endpoints.origin || !endpoints.destination || busy || locating}
          onPress={() => { setOpen(undefined); onSubmit(); }} />
      </View>
      {configured === false && <Text accessibilityLiveRegion="polite" style={[s.statusNotice, { color: theme.warning }]}>
        길찾기 연결을 준비하고 있어요. 장소와 대여소 조회는 계속 이용할 수 있어요.
      </Text>}
      {!!error && <Text accessibilityRole="alert" accessibilityLiveRegion="polite" style={[s.statusNotice, { color: theme.error }]}>{error}</Text>}
      {!route && <View style={[s.nearby, { borderColor: theme.border }]}>
        <Text accessibilityRole="header" style={[s.nearbyHeading, { color: theme.text }]}>
          {endpoints[active] ? `${roleLabel} 주변 대여소` : '지도 주변 대여소'}
        </Text>
        <Text style={[s.nearbyNotice, { color: theme.muted }]}>1km 이내 · 대여 가능한 곳부터 · 직선거리순</Text>
        {nearbyLoading ? <Text accessibilityLiveRegion="polite" style={[s.nearbyNotice, { color: theme.muted }]}>주변 대여소를 찾고 있어요…</Text>
          : nearbyError ? <View style={s.nearbyError}>
            <Text accessibilityLiveRegion="polite" style={[s.noticeText, { color: theme.muted }]}>대여소를 불러오지 못했어요.</Text>
            {onRetryNearby && <Pressable accessibilityRole="button" hitSlop={8} onPress={onRetryNearby}>
              <Text style={[s.noticeText, s.retry, { color: theme.accent }]}>다시 시도</Text>
            </Pressable>}
          </View>
          : nearby.length ? <View style={s.stationList}>
            {nearby.map((station) => {
              const state = availability(station);
              const selected = station.stationId === selectedStationId;
              return <Pressable key={station.stationId} accessibilityRole="button" accessibilityState={{ selected }}
                onPress={() => chooseStation(station)} style={[s.choice, s.nearStation, {
                  backgroundColor: selected ? selectionSurface : theme.surface,
                  borderColor: selected ? SELECTED_BORDER : dark ? theme.field : '#DCE3ED',
                }]}>
                <View style={s.grow}>
                  <Text style={[s.nearStationName, { color: theme.text }]}>{displayStationName(station.stationName)}</Text>
                  <Text style={[s.nearStationDetail, { color: theme.muted }]}>
                    {distanceLabel(station.distanceMeters)} · {AVAILABILITY_LABEL[state]}
                  </Text>
                </View>
                <View style={s.stock}>
                  <Text style={[s.stockCount, { color: theme.accent }]}>
                    {state === 'unknown' ? '수량 미확인' : `${station.parkingBikeTotCnt}대`}
                  </Text>
                  <Text style={[s.nearStationDetail, { color: theme.muted }]}>{roleLabel}로 선택</Text>
                </View>
              </Pressable>;
            })}
          </View> : <Text style={[s.nearbyNotice, { color: theme.muted }]}>1km 이내에 대여소가 없어요. 다른 장소를 검색해 주세요.</Text>}
      </View>}
      {route && <View onLayout={({ nativeEvent }) => onResultLayout(nativeEvent.layout.y)}>
        <RouteResult route={route} routes={routes} routeIndex={routeIndex} onRouteIndex={onRouteIndex}
          endpoints={endpoints} stations={routeStations} onStation={onInspectStation} partial={partial} inventoryError={inventoryError} />
      </View>}
    </View>
  );
}

function RouteResult({ route, routes, routeIndex, onRouteIndex, endpoints, stations, onStation, partial, inventoryError }: {
  route: BikeRoute; routes: BikeRoute[]; routeIndex: number; onRouteIndex: (index: number) => void;
  endpoints: RoutePanelProps['endpoints'];
  stations?: Station[]; onStation: (station: Station) => void; partial?: boolean; inventoryError?: boolean;
}) {
  const theme = useRiderTheme();
  const dark = theme === riderThemes.dark;
  const summaryColor = dark ? theme.blue : theme.navy;
  const selectionSurface = dark ? theme.selected : '#EAF0F8';
  const optionBorder = dark ? theme.field : '#DCE3ED';
  const legEndpoints = [
    endpointTitle(endpoints.origin),
    displayStationName(route.departureStation.stationName),
    displayStationName(route.arrivalStation.stationName),
    endpointTitle(endpoints.destination),
  ];
  return (
    <View>
      {routes.length > 1 && <View style={s.options}>
        {routes.map((candidate, index) => {
          const selected = routeIndex === index;
          return <Pressable key={candidate.routeId} accessibilityRole="button" accessibilityState={{ selected }}
            onPress={() => onRouteIndex(index)} style={[s.choice, s.option, {
              borderColor: selected ? SELECTED_BORDER : optionBorder, backgroundColor: selected ? selectionSurface : theme.surface,
            }]}>
            <Text style={[s.optionHeading, { color: theme.text }]}>
              {index ? `대안 ${index}` : '추천'} · {minutes(candidate.durationSeconds)}
            </Text>
            <Text style={[s.optionDetails, { color: theme.muted }]}>
              {distanceLabel(candidate.distanceMeters)} · 도보 {distanceLabel(candidate.walkingDistanceMeters)}
            </Text>
            <Text style={[s.optionDetails, { color: theme.muted }]}>
              {displayStationName(candidate.departureStation.stationName)} → {displayStationName(candidate.arrivalStation.stationName)}
            </Text>
          </Pressable>;
        })}
      </View>}
      {partial && <Text accessibilityLiveRegion="polite" style={[s.statusNotice, { color: theme.warning }]}>
        일부 대여소 조합은 경로 서비스 응답이 없어 비교하지 못했어요.
      </Text>}
      {inventoryError && <Text accessibilityLiveRegion="polite" style={[s.statusNotice, { color: theme.warning }]}>
        경로 대여·반납 대여소의 수량을 갱신하지 못했어요.
      </Text>}
      <View style={[s.summary, { backgroundColor: dark ? theme.selected : '#EDF3FA' }]}>
        {[{ label: '예상 이동 시간', value: minutes(route.durationSeconds) }, { label: '총 이동 거리', value: distanceLabel(route.distanceMeters) }]
          .map(({ label, value }) => <View key={label}><Text style={{ color: theme.muted, fontSize: 11 }}>{label}</Text>
            <Text style={[s.summaryValue, { color: summaryColor }]}>{value}</Text></View>)}
      </View>
      <Text style={[s.routeNote, { color: theme.muted }]}>
        대여·반납 시간은 별도예요. 가까운 대여소 후보 중 이동 시간이 짧은 경로를 골랐어요.
      </Text>
      <View style={s.legs}>
        {route.legs.map((leg, index) => {
          const rental = index === 0;
          const station = index < 2 ? stations?.[index] ?? (rental ? route.departureStation : route.arrivalStation) : undefined;
          const bike = leg.mode === 'bicycle';
          const color = bike ? dark ? '#7BD5E0' : '#00758F' : dark ? theme.blue : '#2563A6';
          return <View key={index} style={[s.leg, bike && s.bikeLeg]}>
            {bike ? <View pointerEvents="none" style={s.bikeLine} /> : <View pointerEvents="none" style={s.walkLine}>
              {/* Clip to one edge so the dashed border uses equal widths on iOS. */}
              <View style={s.dashedBorder} />
            </View>}
            <View style={s.legHeading}>
              <RiderIcon name={bike ? 'bike' : 'walk'} size={21} color={color} />
              <Text style={[s.legTitle, { color }]}>{bike ? '자전거' : '도보'} {minutes(leg.durationSeconds)}</Text>
              <Text style={{ color: theme.muted, fontSize: 12 }}>{distanceLabel(leg.distanceMeters)}</Text>
            </View>
            <Text style={[s.legPath, { color: theme.text }]}>{legEndpoints[index]} → {legEndpoints[index + 1]}</Text>
            {station && <Pressable accessibilityRole="button" onPress={() => onStation(station)}
              style={[s.station, { backgroundColor: dark ? theme.raised : '#F3F6FA' }]}>
              <Text style={[s.nearStationName, { color: theme.text }]}>
                {rental ? '대여' : '반납'} · {displayStationName(station.stationName)}
              </Text>
              <Text style={[s.noticeText, { color: theme.muted }]}>
                {rental ? rentalObservation(route) : '현장에서 반납 가능 여부를 확인해 주세요.'}
              </Text>
            </Pressable>}
            {!!leg.steps.length && <RouteSteps steps={leg.steps} />}
          </View>;
        })}
      </View>
      <Text style={[s.routeNote, { color: theme.muted }]}>
        수량은 이동 중 바뀔 수 있어요. 출발 전에 다시 확인해 주세요. 지도 경로의 예상 시간이며 현장 통행 표지를 우선해 주세요.
      </Text>
      {!!route.attribution && <Text style={[s.attribution, { color: theme.muted }]}>{route.attribution}</Text>}
    </View>
  );
}

function rentalObservation({ departureStation, rentalAvailability }: BikeRoute): string {
  const count = departureStation.parkingBikeTotCnt === null ? '수량 미확인' : `조회 시 ${departureStation.parkingBikeTotCnt}대`;
  const note = rentalAvailability === 'UNAVAILABLE_AT_FETCH' ? ' · 조회 시 대여 가능한 자전거 없음'
    : rentalAvailability === 'UNKNOWN' ? ' · 대여 가능 여부 미확인' : '';
  return `${count} · ${observationLabel(departureStation)}${note}`;
}

function RouteSteps({ steps }: { steps: RouteStep[] }) {
  const theme = useRiderTheme();
  const [expanded, setExpanded] = useState(false);
  return <View style={s.steps}>
    <Pressable accessibilityRole="button" accessibilityState={{ expanded }} onPress={() => setExpanded(!expanded)}>
      <Text style={[s.stepText, { color: theme.accent }]}>{expanded ? '▾' : '▸'} 구간별 안내</Text>
    </Pressable>
    {expanded && steps.map((step, index) => <Text key={index} style={[s.stepText, s.stepItem, { color: theme.text }]}>
      {index + 1}. {step.instruction} <Text style={{ color: theme.muted, fontSize: 10 }}>{distanceLabel(step.distanceMeters)}</Text>
    </Text>)}
  </View>;
}

const s = StyleSheet.create({
  panel: { paddingHorizontal: 22, paddingVertical: 18 },
  eyebrow: { fontSize: 10, fontWeight: '600', letterSpacing: 1.2 },
  headline: { fontSize: 21, fontWeight: '700', letterSpacing: -0.7, marginTop: 8 },
  intro: { fontSize: 12, lineHeight: 20, marginTop: 10 },
  endpoint: { marginTop: 16 },
  endpointExpanded: { zIndex: 1 },
  suggestions: { position: 'absolute', top: '100%', left: 0, right: 0 },
  label: { fontWeight: '700', fontSize: 13, marginBottom: 8 },
  selected: { marginTop: 7, fontSize: 11, lineHeight: 18 },
  tools: { padding: 12, marginTop: 14, borderWidth: 1, borderRadius: 10 },
  toolTarget: { fontSize: 11, fontWeight: '600' },
  savedRow: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 8 },
  chips: { flexDirection: 'row', alignItems: 'center', gap: 6, paddingVertical: 3, paddingHorizontal: 2 },
  chip: { borderWidth: 1, borderRadius: 8, minHeight: 32, paddingVertical: 5, paddingHorizontal: 9, justifyContent: 'center' },
  chipLabel: { fontSize: 12 },
  bold: { fontWeight: '700' },
  toolsActions: { flexDirection: 'row', gap: 20, alignItems: 'center' },
  toolAction: { flexDirection: 'row', gap: 5, alignItems: 'center', minHeight: 44 },
  grow: { flex: 1 },
  pinned: { flexDirection: 'row', alignItems: 'center', gap: 8, borderRadius: 10, padding: 12, marginTop: 15 },
  pinnedName: { marginTop: 5, fontSize: 12, lineHeight: 18, fontWeight: '700' },
  unpin: { padding: 8 },
  submit: { marginTop: 22 },
  noticeText: { fontSize: 11, lineHeight: 19 },
  statusNotice: { fontSize: 11, lineHeight: 19, paddingVertical: 12 },
  accuracyNotice: { paddingBottom: 0 },
  nearby: { marginTop: 28, paddingTop: 20, borderTopWidth: 1 },
  nearbyHeading: { fontSize: 15, fontWeight: '700' },
  nearbyNotice: { fontSize: 11, lineHeight: 19, marginTop: 8, marginBottom: 12 },
  nearbyError: { flexDirection: 'row', flexWrap: 'wrap', gap: 4, marginTop: 8, marginBottom: 12 },
  retry: { textDecorationLine: 'underline' },
  stationList: { gap: 8 },
  choice: { borderWidth: 1, borderRadius: 10, paddingHorizontal: 12 },
  nearStation: { flexDirection: 'row', alignItems: 'center', gap: 12, paddingVertical: 13 },
  nearStationName: { fontSize: 12, lineHeight: 19, fontWeight: '700' },
  nearStationDetail: { fontSize: 10, marginTop: 4 },
  stock: { alignItems: 'flex-end' },
  stockCount: { fontSize: 14, fontWeight: '700' },
  options: { gap: 8, marginTop: 18 },
  option: { gap: 3, paddingVertical: 11 },
  optionHeading: { fontSize: 13, fontWeight: '700' },
  optionDetails: { fontSize: 10 },
  summary: { flexDirection: 'row', flexWrap: 'wrap', padding: 18, gap: 35, borderRadius: 12, marginTop: 24 },
  summaryValue: { fontWeight: '700', fontSize: 24, marginTop: 5 },
  routeNote: { marginVertical: 13, fontSize: 11, lineHeight: 20 },
  legs: { gap: 15 },
  leg: { paddingLeft: 17, paddingTop: 8, paddingBottom: 16 },
  bikeLeg: { paddingLeft: 18 },
  bikeLine: { position: 'absolute', top: 0, bottom: 0, left: 0, width: 4, backgroundColor: '#0087A8' },
  walkLine: { position: 'absolute', top: 0, bottom: 0, left: 0, width: 3, overflow: 'hidden' },
  dashedBorder: { position: 'absolute', top: -3, bottom: -3, left: 0, width: 20, borderWidth: 3, borderStyle: 'dashed', borderColor: '#2563A6' },
  legHeading: { flexDirection: 'row', alignItems: 'center', gap: 7 },
  legTitle: { fontSize: 14, fontWeight: '700', flex: 1 },
  legPath: { fontSize: 12, lineHeight: 22, marginTop: 10 },
  station: { borderRadius: 8, gap: 4, padding: 12, marginTop: 10 },
  steps: { marginTop: 10 },
  stepText: { fontSize: 12, lineHeight: 22 },
  stepItem: { marginLeft: 19, marginTop: 8 },
  attribution: { fontSize: 10, lineHeight: 17 },
});
