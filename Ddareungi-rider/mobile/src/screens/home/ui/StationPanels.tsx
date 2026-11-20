import { useState, type ReactNode } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Station } from '../../../entities/station/model/types';
import { FORECAST_HORIZONS } from '../../../features/forecast/api/forecastApi';
import type { Forecast, Place, RouteEndpointRole } from '../../../shared/rider-contract/types';
import type { RecentPlace } from '../../../shared/rider-contract/recentPlaceStorage';
import { displayStationName } from '../../../shared/rider-contract/stationName';
import { availability, AVAILABILITY_LABEL, distanceLabel, endpointActions, observationLabel, stationOutlook } from '../../../shared/rider-contract/status';
import { InventoryComparison } from './InventoryComparison';
import { SearchMatchText } from './MapSearch';
import { StationForecast } from './StationForecast';
import { RiderIcon, type RiderIconName } from './MapChrome';
import { useRiderTheme } from './theme';

const RADII = [500, 1000, 1500, 3000];
const HORIZONS = [0, ...FORECAST_HORIZONS];

export function SheetButton({ label, onPress, disabled, icon, directions, hint }: {
  label: string; onPress: () => void; disabled?: boolean; icon?: RiderIconName; directions?: boolean; hint?: string | null;
}) {
  return (
    <Pressable accessibilityRole="button" accessibilityHint={hint ?? undefined} accessibilityState={{ disabled }}
      onPress={onPress} disabled={disabled}
      style={[s.primary, { opacity: disabled ? (directions ? 0.5 : 0.55) : 1 }]}>
      {icon && <RiderIcon name={icon} size={17} color="#FFFFFF" />}
      <Text style={[s.primaryLabel, directions && s.directionsLabel]}>{label}</Text>
    </Pressable>
  );
}

export function IconButton({ name, label, onPress, size = 18, color, disabled, large, selected }: {
  name: RiderIconName; label: string; onPress: () => void; size?: number; color?: string; disabled?: boolean;
  large?: boolean; selected?: boolean;
}) {
  const theme = useRiderTheme();
  return <Pressable accessibilityRole="button" accessibilityLabel={label} accessibilityState={{ selected }}
    disabled={disabled} onPress={onPress} style={[s.iconButton, large && s.largeIconButton]}>
    <RiderIcon name={name} size={size} color={color ?? theme.quiet} />
  </Pressable>;
}

function inventoryColors(theme: ReturnType<typeof useRiderTheme>, state: ReturnType<typeof availability>) {
  if (state === 'low') return { ink: theme.warning, background: theme.warningSurface };
  if (state === 'empty') return { ink: theme.negative, background: theme.negativeSurface };
  if (state === 'unknown' || state === 'stale') return { ink: theme.muted, background: theme.raised };
  return { ink: theme.blue, background: theme.selected };
}

function InventoryTag({ station, now }: { station: Station; now: number }) {
  const theme = useRiderTheme();
  const state = availability(station, now);
  const { ink, background } = inventoryColors(theme, state);
  return <View style={[s.tag, { backgroundColor: background }, state === 'stale' && [s.stale, { borderColor: ink }]]}>
    <View style={[s.tagDot, { backgroundColor: ink }, state === 'stale' && { backgroundColor: 'transparent', borderColor: ink, borderWidth: 1 }]} />
    <Text style={{ color: ink, fontSize: 10, fontWeight: '600', includeFontPadding: false }}>{AVAILABILITY_LABEL[state]}</Text>
  </View>;
}

function StationRow({ station, now, saved, term, forecastMinutes, predictedCount, onSelect, onSave }: {
  station: Station; now: number; saved: boolean; term: string; onSelect: () => void; onSave: () => void;
  forecastMinutes: number; predictedCount: number | null;
}) {
  const theme = useRiderTheme();
  const state = availability(station, now);
  const colors = inventoryColors(theme, state);
  const count = state === 'unknown' ? '—' : station.parkingBikeTotCnt;
  const name = displayStationName(station.stationName);
  return (
    <View style={[s.stationCard, { borderColor: theme.border, backgroundColor: theme.surface }]}>
      <Pressable accessibilityRole="button" accessibilityLabel={`${name} 상세 보기`} onPress={onSelect} style={s.stationRow}>
        <View style={[s.row, s.stationHeader]}>
          <InventoryTag station={station} now={now} />
          <Text style={{ color: theme.muted, fontSize: 10 }}>{distanceLabel(station.distanceMeters)}</Text>
        </View>
        <View style={[s.row, s.stationHeading]}>
          <Text style={[s.stationName, { color: theme.text }]}><SearchMatchText name={name} term={term} /></Text>
          <Text style={[s.stationStock, { color: colors.ink, opacity: state === 'stale' ? 0.75 : 1 }]}>
            {count}<Text style={s.stationUnit}> 대</Text>
          </Text>
        </View>
        {forecastMinutes > 0 && <Text style={[s.stationPrediction, { color: theme.blue }]}>
          {forecastMinutes}분 후 예상 · {predictedCount === null ? '예측 정보 없음' : `약 ${predictedCount}대`}
          <Text style={{ color: theme.muted, fontSize: 9, fontWeight: '400' }}> (위 수량은 현재 기준)</Text>
        </Text>}
        <Text style={[s.stationAddress, { color: theme.muted }]}>{station.address || '주소 정보 없음'}</Text>
        <View style={[s.row, s.stationFooter, { borderTopColor: theme.border }]}>
          <View style={s.observation}>
            <RiderIcon name="clock" size={12} color={theme.muted} />
            <Text style={[s.stationFootnote, { color: theme.muted }]}>{observationLabel(station, now)}</Text>
          </View>
          <View style={s.observation}>
            <Text style={[s.stationFootnote, { color: theme.muted }]}>거치대 {station.rackTotCnt ?? '—'}개</Text>
            <RiderIcon name="chevron" size={14} color={theme.muted} />
          </View>
        </View>
      </Pressable>
      <View style={s.cardStar}>
        <IconButton name={saved ? 'star-saved' : 'star'} label={`${name} ${saved ? '즐겨찾기 해제' : '즐겨찾기 추가'}`}
          onPress={onSave} size={19} large selected={saved} />
      </View>
    </View>
  );
}

export function ListPanel({
  favorites, sample, title, rows, radius, changeRadius, filter, changeFilter, forecast, skeleton, refresh, now,
  favoriteIds, missingFavorites, select, toggleFavorite, subway, subwayMessage, chooseSubway, reliableLocation, recent, onRecent, clearRecent,
  searched, empty, notice, footer, selectedPlace, choosePlaceEndpoint, nearbyLimit,
  moreLabel, showMore,
}: {
  favorites: boolean; sample: boolean; title: string; rows: Station[]; radius: number; changeRadius: (radius: number) => void;
  filter: boolean; changeFilter: () => void; skeleton: boolean; refresh: () => void; now: number;
  forecast: { minutes: number; change: (value: number) => void; effectiveMinutes: number; notice?: string; forecastMap: ReadonlyMap<string, Forecast> };
  favoriteIds: string[]; missingFavorites: string[]; select: (station: Station) => void; toggleFavorite: (id: string) => void;
  subway: Place[]; subwayMessage?: string; chooseSubway: (place: Place) => void; reliableLocation: boolean;
  recent: RecentPlace[]; onRecent: (query: string) => void; clearRecent: () => void; searched: string;
  empty: boolean; notice?: ReactNode; footer?: ReactNode; selectedPlace?: Place; nearbyLimit: number | null;
  choosePlaceEndpoint?: (role: RouteEndpointRole) => void;
  moreLabel?: string; showMore: () => void;
}) {
  const theme = useRiderTheme();
  const [horizons, setHorizons] = useState(false);
  const [legend, setLegend] = useState(false);
  return (
    <View style={s.list}>
      {selectedPlace && <View style={[s.searchedPlace, { borderColor: theme.border }]}>
        <Text style={[s.eyebrow, { color: theme.quiet }]}>선택한 장소</Text>
        <Text style={{ fontWeight: '700', color: theme.text, fontSize: 15 }}>{selectedPlace.name}</Text>
        {!!selectedPlace.address && <Text style={{ color: theme.quiet, fontSize: 11 }}>{selectedPlace.address}</Text>}
        <View style={s.twoColumns}>
          <View style={s.column}><SheetButton label="출발지로" onPress={() => choosePlaceEndpoint?.('origin')} /></View>
          <View style={s.column}><SheetButton label="도착지로" onPress={() => choosePlaceEndpoint?.('destination')} /></View>
        </View>
      </View>}
      <View style={s.row}>
        <View style={s.column}>
          <Text style={[s.listEyebrow, { color: theme.quiet }]}>
            {searched ? '검색 결과' : favorites ? '나만의 자전거 정류장' : 'NEARBY STATIONS'}
          </Text>
          <View style={s.headingRow}>
            <Text numberOfLines={1} style={[s.heading, { color: theme.text }]} accessibilityRole="header">{title}</Text>
            <Text style={[s.headingCount, { color: theme.muted, backgroundColor: theme.selected }]}>{rows.length}</Text>
          </View>
        </View>
        <IconButton name="refresh" label="대여소 새로고침" onPress={refresh} />
      </View>
      {!favorites && !searched && <View style={s.radii} accessibilityLabel="검색 반경">
        {RADII.map((value) => {
          const active = radius === value;
          return <Pressable key={value} accessibilityRole="button" accessibilityState={{ selected: active }}
            onPress={() => changeRadius(value)} style={[s.radius, {
              backgroundColor: active ? theme.selected : theme.surface, borderColor: active ? '#96A7BF' : theme.border,
            }]}>
            <Text style={{ fontWeight: active ? '700' : '400', fontSize: 11, color: active ? theme.blue : theme.quiet }}>
              {value < 1000 ? `${value}m` : `${value / 1000}km`}
            </Text>
          </Pressable>;
        })}
      </View>}
      <View style={[s.forecast, (favorites || searched) && s.forecastNoRadii]}>
        <View style={s.forecastRow}>
          <Text style={{ color: theme.text, fontSize: 13, minWidth: 85 }}>대여 예정 시간</Text>
          <Pressable accessibilityRole="button" accessibilityLabel="대여 예정 시간" accessibilityState={{ expanded: horizons }}
            onPress={() => setHorizons((open) => !open)} style={[s.forecastSelect, { borderColor: theme.handle }]}>
            <Text style={{ fontSize: 13, color: theme.text }}>{forecast.minutes ? `${forecast.minutes}분 후` : '지금'}</Text>
            <View style={s.downArrow}><RiderIcon name="chevron" size={12} color={theme.navy} /></View>
          </Pressable>
        </View>
        {horizons && <View style={[s.horizons, { borderColor: theme.border }]}>
          {HORIZONS.map((minute) => <Pressable accessibilityRole="button" key={minute}
            onPress={() => { forecast.change(minute); setHorizons(false); }} style={s.horizon}>
            <Text style={{ color: forecast.minutes === minute ? theme.accent : theme.text, fontSize: 12 }}>{minute ? `${minute}분 후` : '지금'}</Text>
          </Pressable>)}
        </View>}
        {forecast.notice && <Text accessibilityLiveRegion="polite" style={{ color: theme.muted, fontSize: 13, lineHeight: 19.5 }}>
          {forecast.notice}
        </Text>}
      </View>
      {!!recent.length && <View style={s.shortcuts}>
        <Text style={[s.shortcutLabel, { color: theme.muted }]}>최근 장소</Text>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled" contentContainerStyle={s.chips}>
          {recent.slice(0, 2).map(({ query }) => <Pressable key={query} accessibilityRole="button" onPress={() => onRecent(query)}
            style={[s.chip, { backgroundColor: theme.raised }]}>
            <Text numberOfLines={1} style={{ color: theme.muted, fontSize: 10, maxWidth: 104 }}>{query}</Text>
          </Pressable>)}
        </ScrollView>
        <IconButton name="close" label="최근 장소 지우기" size={12} large onPress={clearRecent} />
      </View>}
      <View style={s.shortcuts}>
        <Text style={[s.shortcutLabel, { color: theme.muted }]}>{reliableLocation ? '내 주변역' : '지도 주변역'}</Text>
        {!!subway.length && <ScrollView horizontal showsHorizontalScrollIndicator={false} keyboardShouldPersistTaps="handled" contentContainerStyle={s.chips}>
          {subway.map((item, i) => <Pressable key={`${item.name}:${i}`} accessibilityRole="button" accessibilityHint={item.address}
            onPress={() => chooseSubway(item)} style={[s.chip, { backgroundColor: theme.raised }]}>
            <Text numberOfLines={1} style={{ color: theme.muted, fontSize: 10, maxWidth: 104 }}>{item.name}</Text>
          </Pressable>)}
        </ScrollView>}
        {!!subwayMessage && <Text style={[s.shortcutLabel, { color: theme.muted }]} accessibilityLiveRegion="polite">{subwayMessage}</Text>}
      </View>
      <Pressable style={s.legend} accessibilityRole="button" accessibilityState={{ expanded: legend }} onPress={() => setLegend((show) => !show)}>
        <Text style={{ color: theme.muted, fontSize: 11 }}>{legend ? '▾' : '▸'} 지도 수량 표시 안내</Text>
      </Pressable>
      {legend && <Text style={[s.caption, { color: theme.muted }]}>
        파랑 4대 이상 · 노랑 1–3대 · 빨강 0대 · 회색 점선 갱신 지연(대여 가능 여부 미확인) · 회색 정보 없음
      </Text>}
      <View style={[s.row, s.filters]}>
        <Text style={{ color: theme.quiet, fontSize: 10, flex: 1 }}>
          {favorites && !searched ? '이 기기에 저장돼요' : !searched && nearbyLimit !== null
            ? `대여 가능 우선 · ${nearbyLimit}곳 · 그룹 내 거리순` : '대여 가능 → 0대 → 정보 없음 · 그룹 내 거리순'}
        </Text>
        <Pressable style={s.checkbox} accessibilityRole="checkbox" accessibilityState={{ checked: filter }} onPress={changeFilter}>
          <View style={[s.checkboxMark, { borderColor: theme.quiet, backgroundColor: filter ? theme.accent : theme.surface }]}>
            {filter && <Text style={{ color: '#FFFFFF', fontSize: 9 }}>✓</Text>}
          </View>
          <Text style={{ color: theme.muted, fontSize: 10 }}>{forecast.effectiveMinutes > 0 ? '예상 대여 가능만' : '대여 가능만'}</Text>
        </Pressable>
      </View>
      {sample && <View style={[s.sampleNotice, { backgroundColor: theme.warningSurface }]}>
        <RiderIcon name="info" size={15} color={theme.warning} />
        <Text style={[s.sampleText, { color: theme.warning }]}>샘플 모드 · 표시 수량은 실제 데이터가 아니에요.</Text>
      </View>}
      {notice}
      {skeleton && <View accessibilityLabel="대여소 불러오는 중" accessibilityLiveRegion="polite">
        {[1, 2, 3].map((key) => <View key={key} style={[s.skeleton, { backgroundColor: theme.raised }]} />)}
      </View>}
      {rows.map((station) => <StationRow key={station.stationId} station={station} now={now}
        term={searched}
        forecastMinutes={forecast.effectiveMinutes}
        predictedCount={stationOutlook(station, forecast.forecastMap.get(station.stationId), forecast.effectiveMinutes, now).count}
        saved={favoriteIds.includes(station.stationId)} onSelect={() => select(station)}
        onSave={() => toggleFavorite(station.stationId)} />)}
      {moreLabel && <Pressable accessibilityRole="button" onPress={showMore}
        style={[s.showMore, { borderColor: theme.border, backgroundColor: theme.selected }]}>
        <Text style={{ color: theme.accent, fontSize: 12 }}>{moreLabel}</Text>
      </Pressable>}
      {empty && <View style={s.empty}>
        <RiderIcon name={favorites ? 'star' : 'bike'} color={theme.muted} size={35} />
        <Text style={[s.emptyTitle, { color: theme.muted }]}>
          {favorites && !searched ? '자주 가는 대여소를 저장해 보세요' : '조건에 맞는 대여소가 없어요'}
        </Text>
        <Text style={[s.emptyCopy, { color: theme.muted }]}>
          {favorites ? '대여소 옆 별을 누르면 여기에 모아볼 수 있어요.' : '검색 반경을 넓히거나 다른 장소를 찾아보세요.'}
        </Text>
      </View>}
      {missingFavorites.map((id) => <View key={id} style={[s.row, s.missingFavorite]}>
        <Text style={{ color: theme.text, fontSize: 11 }}>{id} · 대여소 정보 없음</Text>
        <Pressable accessibilityRole="button" accessibilityLabel={`${id} 저장 해제`} onPress={() => toggleFavorite(id)}>
          <Text style={{ color: theme.accent, fontSize: 11 }}>저장 해제</Text>
        </Pressable>
      </View>)}
      <View style={s.listFootnote}>
        <RiderIcon name="refresh" color={theme.muted} size={12} />
        <Text style={{ color: theme.muted, fontSize: 9 }}>화면을 보고 있는 동안 1분마다 수량을 확인해요</Text>
      </View>
      {footer}
    </View>
  );
}

export function StationPanel({ station, now, sample, modeUnknown, retryMode, saved, onSave, onClose, onEndpoint, detailNotice }: {
  station: Station; now: number; sample: boolean; modeUnknown: boolean; retryMode?: () => void;
  saved: boolean; onSave: () => void; onClose: () => void;
  onEndpoint: (role: RouteEndpointRole) => void; detailNotice?: string;
}) {
  const theme = useRiderTheme();
  const state = modeUnknown ? 'unknown' : availability(station, now);
  const { ink, background } = inventoryColors(theme, state);
  const blockWhileUnknown = { disabled: true, reason: '데이터 모드를 확인한 뒤 길찾기를 이용해 주세요.' };
  const actions = modeUnknown ? { departure: blockWhileUnknown, arrival: blockWhileUnknown } : endpointActions(station, sample, now);
  const sharedReason = actions.departure.reason && actions.departure.reason === actions.arrival.reason;
  const count = state === 'unknown' ? '—' : station.parkingBikeTotCnt;
  return (
    <View style={s.detail}>
      <View style={[s.row, s.detailTop]}>
        <Text style={[s.eyebrow, { color: theme.quiet }]}>선택한 대여소</Text>
        <View style={s.detailIcons}>
          <IconButton name={saved ? 'star-saved' : 'star'} label={saved ? '즐겨찾기 해제' : '즐겨찾기 추가'}
            onPress={onSave} size={20} large selected={saved} />
          <IconButton name="close" label="대여소 상세 닫기" onPress={onClose} size={20} large />
        </View>
      </View>
      <Text accessibilityRole="header" textBreakStrategy="balanced" style={[s.stationTitle, { color: theme.text }]}>
        {displayStationName(station.stationName)}
      </Text>
      <Text style={[s.address, { color: theme.muted }]}>{station.address || '주소 정보 없음'}</Text>
      <View style={[s.inventory, { backgroundColor: background, borderColor: ink }, state === 'stale' && s.stale]}>
        <View>
          <Text style={{ fontSize: 10, color: ink }}>{modeUnknown ? '대여 수량의 데이터 모드 확인 중'
            : sample ? '샘플 대여 수량 · 실제 데이터 아님' : '지금 대여 가능한 자전거'}</Text>
          <View style={[s.countRow, state === 'stale' && { opacity: 0.75 }]}>
            <Text style={{ fontSize: 43, lineHeight: 56, fontWeight: '700', color: ink, includeFontPadding: false }}>{count}</Text>
            <Text style={{ fontSize: 12, color: ink }}>{state === 'unknown' ? '정보 없음' : '대'}</Text>
          </View>
        </View>
        <View style={{ opacity: 0.55 }}><RiderIcon name="bike" size={38} color={ink} /></View>
      </View>
      <View style={[s.row, s.detailMeta]}>
        {!modeUnknown && <>
          <InventoryTag station={station} now={now} />
          <View style={s.observation}><RiderIcon name="clock" size={13} color={theme.muted} />
            <Text style={{ color: theme.muted, fontSize: 9 }}>{observationLabel(station, now)}</Text>
          </View>
        </>}
        {retryMode && <Pressable accessibilityRole="button" onPress={retryMode}>
          <Text accessibilityLiveRegion="polite" style={[s.caption, { color: theme.accent }]}>
            데이터 모드를 확인하지 못했어요. 다시 시도
          </Text>
        </Pressable>}
      </View>
      <View style={[s.facts, { borderBottomColor: theme.border }]}>
        <View style={s.fact}>
          <RiderIcon name="walk" color={theme.muted} />
          <View>
            <Text style={{ color: theme.muted, fontSize: 9 }}>검색 위치에서</Text>
            <Text style={[s.factValue, { color: theme.text }]}>{distanceLabel(station.distanceMeters)}
              <Text style={{ color: theme.muted, fontSize: 8, fontWeight: '400' }}> 직선거리</Text>
            </Text>
          </View>
        </View>
        <View style={s.fact}>
          <RiderIcon name="bike" color={theme.muted} />
          <View>
            <Text style={{ color: theme.muted, fontSize: 9 }}>거치대</Text>
            <Text style={[s.factValue, { color: theme.text }]}>{station.rackTotCnt ?? '—'}개</Text>
          </View>
        </View>
      </View>
      <InventoryComparison station={station} sample={sample} modeUnknown={modeUnknown} />
      {!sample && !modeUnknown && <StationForecast stationId={station.stationId} now={now} />}
      <View style={[s.twoColumns, s.directions]}>
        <View style={s.column}><SheetButton label="출발지" directions disabled={actions.departure.disabled}
          hint={actions.departure.reason} onPress={() => onEndpoint('origin')} /></View>
        <View style={s.column}><SheetButton label="도착지" directions disabled={actions.arrival.disabled}
          hint={actions.arrival.reason} onPress={() => onEndpoint('destination')} /></View>
      </View>
      {(actions.departure.reason || actions.arrival.reason || detailNotice) && <View style={s.actionNotes} accessibilityLiveRegion="polite">
        {sharedReason ? <Text style={[s.caption, { color: theme.muted }]}>{actions.departure.reason}</Text> : <>
          {actions.departure.reason && <Text style={[s.caption, { color: theme.muted }]}>출발: {actions.departure.reason}</Text>}
          {actions.arrival.reason && <Text style={[s.caption, { color: theme.muted }]}>도착: {actions.arrival.reason}</Text>}
        </>}
        {detailNotice && <Text style={[s.caption, { color: theme.muted }]}>{detailNotice}</Text>}
      </View>}
    </View>
  );
}

const s = StyleSheet.create({
  list: { paddingHorizontal: 20, paddingTop: 16, paddingBottom: 24 },
  detail: { paddingHorizontal: 22, paddingTop: 5, paddingBottom: 28 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
  column: { flex: 1 },
  twoColumns: { flexDirection: 'row', gap: 8 },
  eyebrow: { fontSize: 10, letterSpacing: 1.2, fontWeight: '600', includeFontPadding: false },
  listEyebrow: { fontSize: 8, letterSpacing: 1.2, fontWeight: '600', includeFontPadding: false },
  headingRow: { flexDirection: 'row', alignItems: 'center', gap: 9, marginTop: 8 },
  heading: { fontSize: 20, letterSpacing: -0.65, fontWeight: '700', flexShrink: 1, includeFontPadding: false },
  headingCount: { borderRadius: 6, paddingVertical: 4, paddingHorizontal: 7, fontSize: 12, fontWeight: '600' },
  iconButton: { width: 36, height: 36, alignItems: 'center', justifyContent: 'center' },
  largeIconButton: { width: 44, height: 44 },
  radii: { flexDirection: 'row', gap: 6, marginTop: 15 },
  radius: { flex: 1, height: 36, borderWidth: 1, borderRadius: 7, alignItems: 'center', justifyContent: 'center' },
  forecast: { marginTop: 20, marginBottom: 18 },
  forecastNoRadii: { marginTop: 20 },
  forecastRow: { flexDirection: 'row', alignItems: 'center', gap: 16 },
  forecastSelect: { minWidth: 132, height: 34, borderRadius: 8, borderWidth: 1, flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', paddingHorizontal: 12 },
  downArrow: { transform: [{ rotate: '90deg' }] },
  horizons: { flexDirection: 'row', flexWrap: 'wrap', borderWidth: 1, borderRadius: 8, marginTop: 7 },
  horizon: { minHeight: 38, paddingHorizontal: 10, justifyContent: 'center' },
  shortcuts: { flexDirection: 'row', alignItems: 'center', gap: 6, marginBottom: 6 },
  shortcutLabel: { fontSize: 10, flexShrink: 0 },
  chips: { gap: 6, alignItems: 'center' },
  chip: { borderRadius: 5, paddingVertical: 5, paddingHorizontal: 8, minHeight: 24, justifyContent: 'center' },
  legend: { paddingVertical: 8, alignSelf: 'flex-start' },
  caption: { fontSize: 11, lineHeight: 17 },
  filters: { gap: 6, marginTop: 14, marginBottom: 10 },
  sampleNotice: { flexDirection: 'row', alignItems: 'center', gap: 5, paddingHorizontal: 8, paddingVertical: 9, borderRadius: 7, marginBottom: 12 },
  sampleText: { flexShrink: 1, fontSize: 9 },
  checkbox: { minHeight: 34, flexDirection: 'row', gap: 3, alignItems: 'center' },
  checkboxMark: { width: 12, height: 12, borderWidth: 1, borderRadius: 2, alignItems: 'center', justifyContent: 'center' },
  stationCard: { borderWidth: 1, borderRadius: 12, marginBottom: 12 },
  stationRow: { padding: 16 },
  stationHeader: { paddingRight: 31 },
  stationHeading: { marginTop: 11, gap: 10 },
  stationName: { flexShrink: 1, fontSize: 14, lineHeight: 20.3, letterSpacing: -0.5, fontWeight: '700', includeFontPadding: false },
  stationStock: { fontSize: 26, fontWeight: '700', includeFontPadding: false },
  stationUnit: { fontSize: 10, fontWeight: '400' },
  stationPrediction: { fontSize: 10, fontWeight: '600', marginTop: 5 },
  stationAddress: { fontSize: 10, lineHeight: 16, marginTop: 5, includeFontPadding: false },
  stationFooter: { borderTopWidth: 1, marginTop: 14, paddingTop: 11 },
  stationFootnote: { fontSize: 9, includeFontPadding: false },
  cardStar: { position: 'absolute', top: 5, right: 3 },
  empty: { paddingVertical: 35, paddingHorizontal: 6, alignItems: 'center' },
  emptyTitle: { fontSize: 13, marginTop: 16, marginBottom: 9, fontWeight: '500', textAlign: 'center' },
  emptyCopy: { fontSize: 10, lineHeight: 17, textAlign: 'center' },
  skeleton: { height: 145, borderRadius: 12, marginBottom: 12 },
  missingFavorite: { paddingVertical: 16 },
  listFootnote: { flexDirection: 'row', gap: 5, alignItems: 'center', justifyContent: 'center', paddingVertical: 16 },
  showMore: { minHeight: 44, borderWidth: 1, borderRadius: 10, padding: 14, marginTop: 8, alignItems: 'center', justifyContent: 'center' },
  searchedPlace: { borderWidth: 1, borderRadius: 10, padding: 12, marginBottom: 15, gap: 5 },
  detailTop: { marginBottom: 8 },
  detailIcons: { flexDirection: 'row', gap: 2, minHeight: 44, alignItems: 'center' },
  stationTitle: { fontSize: 22, lineHeight: 33, letterSpacing: -0.6, fontWeight: '700', includeFontPadding: false },
  address: { marginTop: 6, fontSize: 10, lineHeight: 16 },
  inventory: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', borderRadius: 11, padding: 17, marginTop: 16 },
  stale: { borderWidth: 1, borderStyle: 'dashed' },
  countRow: { flexDirection: 'row', alignItems: 'baseline', marginTop: 6, gap: 6 },
  detailMeta: { marginTop: 11 },
  observation: { flexDirection: 'row', alignItems: 'center', gap: 4 },
  tag: { paddingVertical: 5, paddingHorizontal: 6, gap: 5, borderRadius: 5, flexDirection: 'row', alignItems: 'center', alignSelf: 'flex-start', minHeight: 22 },
  tagDot: { width: 5, height: 5, borderRadius: 3 },
  facts: { flexDirection: 'row', gap: 25, paddingVertical: 17, marginBottom: 16, borderBottomWidth: 1 },
  fact: { flexDirection: 'row', alignItems: 'center', gap: 8 },
  factValue: { fontSize: 13, fontWeight: '700', marginTop: 4 },
  directions: { marginTop: 20 },
  directionsLabel: { fontSize: 14, lineHeight: 18, fontWeight: '600' },
  actionNotes: { marginTop: 8 },
  primary: { backgroundColor: '#2563A6', minHeight: 48, borderRadius: 9, flexDirection: 'row',
    justifyContent: 'center', alignItems: 'center', paddingHorizontal: 8, gap: 9 },
  primaryLabel: { color: '#FFFFFF', fontWeight: '600', fontSize: 12, textAlign: 'center' },
});
