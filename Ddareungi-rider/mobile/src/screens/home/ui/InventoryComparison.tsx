import { skipToken, useQuery } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { AppState, Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import type { Station } from '../../../entities/station/model/types';
import { fetchInventoryHistory } from '../../../features/station-search/api/stationApi';
import { comparisonRows } from '../../../shared/rider-contract/inventoryComparison';
import { RiderIcon } from './MapChrome';
import { useRiderTheme } from './theme';

const HOURS = Array.from({ length: 24 }, (_, hour) => String(hour).padStart(2, '0'));
const MINUTES = Array.from({ length: 12 }, (_, index) => String(index * 5).padStart(2, '0'));
const WEEKDAY = new Intl.DateTimeFormat('ko-KR', { weekday: 'long', timeZone: 'Asia/Seoul' });
const POPOVER_WIDTH = 200;
const POPOVER_HEIGHT = 256;
const OPTION_HEIGHT = 27;

function TimeColumn({ label, options, selected, onSelect }: {
  label: string; options: string[]; selected: string; onSelect: (value: string) => void;
}) {
  const theme = useRiderTheme();
  const scroll = useRef<ScrollView>(null);
  const initial = useRef(selected);
  return (
    <View style={s.cell}>
      <Text style={[s.timeCaption, { color: theme.muted }]}>{label}</Text>
      <ScrollView ref={scroll} style={[s.timeList, { borderColor: theme.border }]}
        onLayout={() => scroll.current?.scrollTo({ y: Math.max(0, options.indexOf(initial.current) - 2) * OPTION_HEIGHT, animated: false })}>
        {options.map((option) => <Pressable key={option} accessibilityRole="button"
          accessibilityState={{ selected: option === selected }} onPress={() => onSelect(option)}
          style={[s.timeOption, option === selected && { backgroundColor: theme.selected }]}>
          <Text style={{ fontSize: 12, color: option === selected ? theme.accent : theme.text }}>{option}{label}</Text>
        </Pressable>)}
      </ScrollView>
    </View>
  );
}

function ComparisonTimePicker({ value, currentTime, onChange }: {
  value: string; currentTime: string; onChange: (value: string) => void;
}) {
  const theme = useRiderTheme();
  const trigger = useRef<View>(null);
  const [anchor, setAnchor] = useState<{ x: number; y: number; width: number; height: number }>();
  const [space, setSpace] = useState({ width: 0, height: 0 });
  const [hour, setHour] = useState('00');
  const [minute, setMinute] = useState('00');
  const display = value === 'now' ? '지금' : value;

  function open() {
    const [nextHour, nextMinute] = (value === 'now' ? currentTime : value).split(':');
    setHour(nextHour);
    setMinute(nextMinute);
    trigger.current?.measureInWindow((x, y, width, height) => setAnchor({ x, y, width, height }));
  }

  function apply(next: string) {
    setAnchor(undefined);
    onChange(next);
  }

  const below = anchor ? anchor.y + anchor.height + 6 : 0;
  const top = anchor && below + POPOVER_HEIGHT > space.height
    ? Math.max(6, anchor.y - POPOVER_HEIGHT - 6) : below;
  const left = anchor ? Math.max(6, Math.min(anchor.x + anchor.width - POPOVER_WIDTH, space.width - POPOVER_WIDTH - 6)) : 0;
  return <>
    <Pressable ref={trigger} collapsable={false} accessibilityRole="button" accessibilityLabel={`시간대 선택 ${display}`}
      accessibilityState={{ expanded: !!anchor }} onPress={open}
      style={[s.trigger, { borderColor: theme.border, backgroundColor: theme.surface }]}>
      <Text style={{ color: theme.text, fontSize: 12 }}>{display}</Text>
      <View style={s.arrow}><RiderIcon name="chevron" color={theme.text} size={12} /></View>
    </Pressable>
    <Modal visible={!!anchor} transparent onRequestClose={() => setAnchor(undefined)}>
      <View style={s.overlay} onLayout={({ nativeEvent: { layout: { width, height } } }) => setSpace({ width, height })}>
        <Pressable style={StyleSheet.absoluteFill} accessibilityLabel="시간대 선택 닫기" onPress={() => setAnchor(undefined)} />
        {!!space.height && anchor && <View accessibilityViewIsModal
          style={[s.popover, { left, top, borderColor: theme.border, backgroundColor: theme.surface }]}>
          <View style={[s.row, s.pickerColumns]}>
            <TimeColumn label="시" options={HOURS} selected={hour} onSelect={setHour} />
            <TimeColumn label="분" options={MINUTES} selected={minute} onSelect={setMinute} />
          </View>
          <View style={[s.row, s.pickerActions]}>
            <Pressable accessibilityRole="button" onPress={() => apply('now')} style={[s.pickerButton, { backgroundColor: theme.raised }]}>
              <Text style={{ fontSize: 12, color: theme.text }}>지금</Text>
            </Pressable>
            <Pressable accessibilityRole="button" onPress={() => apply(`${hour}:${minute}`)} style={[s.pickerButton, { backgroundColor: '#2563A6' }]}>
              <Text style={{ fontSize: 12, color: '#FFFFFF' }}>적용</Text>
            </Pressable>
          </View>
        </View>}
      </View>
    </Modal>
  </>;
}

export function InventoryComparison({ station, sample, modeUnknown }: {
  station: Station; sample: boolean; modeUnknown: boolean;
}) {
  const theme = useRiderTheme();
  const [selection, setSelection] = useState('now');
  const [now, setNow] = useState(Date.now);
  useEffect(() => {
    const update = () => setNow(Date.now());
    const timer = setInterval(update, 15_000);
    const listener = AppState.addEventListener('change', (state) => { if (state === 'active') update(); });
    return () => { clearInterval(timer); listener.remove(); };
  }, []);
  const base = comparisonRows(station, selection, now)[2];
  const startTime = `${base.cells[0].date}T${base.time}:00+09:00`;
  const history = useQuery({
    queryKey: ['inventory-history', station.stationId, startTime],
    queryFn: !sample && !modeUnknown ? ({ signal }) => fetchInventoryHistory(station.stationId, startTime, signal) : skipToken,
    staleTime: 15_000,
    refetchInterval: 30_000,
  });
  const rows = comparisonRows(station, selection, now, history.data, sample || modeUnknown);
  const weekday = WEEKDAY.format(new Date(`${base.cells[2].date}T00:00:00+09:00`));
  return (
    <View style={s.comparison}>
      <View style={s.heading}>
        <Text accessibilityRole="header" style={[s.title, { color: theme.text }]}>시간대별 수량</Text>
        <ComparisonTimePicker value={selection} currentTime={base.time} onChange={(next) => { setSelection(next); setNow(Date.now()); }} />
      </View>
      <View style={[s.table, { borderColor: theme.border }]}>
        <View style={[s.row, s.tableHead, { backgroundColor: theme.raised, borderBottomColor: theme.border }]}>
          {['시간', '오늘', '어제', '지난주'].map((label) => <View key={label} style={s.cell}>
            <Text style={[s.headText, { color: theme.text }]}>{label}</Text>
            {label === '지난주' && <Text style={[s.subtext, { color: theme.muted }]}>{weekday}</Text>}
          </View>)}
        </View>
        {rows.map((row, index) => <View key={row.minutes} style={[s.row, s.tableRow, {
          borderBottomColor: theme.border,
          borderBottomWidth: index === rows.length - 1 ? 0 : 1,
          backgroundColor: row.minutes === 0 ? (theme.surface === '#FFFFFF' ? '#F2F5F9' : '#3E3E44') : theme.surface,
        }]}>
          <View style={s.cell}>
            <Text style={[s.headText, { color: theme.text }]}>{row.time}</Text>
            <Text style={[s.subtext, { color: theme.muted }]}>
              {row.previousDay ? '이전 날 · ' : row.nextDay ? '다음 날 · ' : ''}
              {row.minutes === 0 ? selection === 'now' ? '갱신 기준' : '선택 기준' : `${row.minutes > 0 ? '+' : ''}${row.minutes}분`}
            </Text>
          </View>
          {row.cells.map((cell, cellIndex) => {
            const color = cell.isLive ? theme.blue : theme.text;
            const missing = cell.isFuture ? '-' : modeUnknown ? '데이터 모드 미확인'
              : !sample && !cell.isLive && history.isError ? '조회 실패'
              : !sample && !cell.isLive && history.isPending ? '조회 중' : '데이터 없음';
            return <View key={cellIndex} style={s.cell}>
              {cell.count === null ? <Text style={[s.missing, { color: theme.muted }]}>{missing}</Text>
                : <Text style={[s.count, { color }]}>{cell.count}<Text style={s.unit}> 대</Text></Text>}
            </View>;
          })}
        </View>)}
      </View>
      {sample && <Text style={[s.note, { color: theme.muted }]}>샘플 모드에서는 실제 수량 비교를 제공하지 않아요.</Text>}
    </View>
  );
}

const s = StyleSheet.create({
  comparison: { marginBottom: 20 },
  heading: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10, marginBottom: 12 },
  title: { fontSize: 14, fontWeight: '700' },
  trigger: { minWidth: 84, minHeight: 36, paddingVertical: 6, paddingHorizontal: 10, borderWidth: 1, borderRadius: 8,
    flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 14 },
  arrow: { transform: [{ rotate: '90deg' }] },
  overlay: { flex: 1 },
  popover: { position: 'absolute', width: POPOVER_WIDTH, borderRadius: 12, borderWidth: 1, padding: 12,
    elevation: 7, shadowColor: '#000000', shadowOpacity: 0.2, shadowRadius: 14, shadowOffset: { width: 0, height: 8 } },
  pickerColumns: { gap: 10 },
  timeCaption: { fontSize: 10, textAlign: 'center', marginBottom: 6 },
  timeList: { height: 168, padding: 3, borderWidth: 1, borderRadius: 6 },
  timeOption: { minHeight: OPTION_HEIGHT, justifyContent: 'center', paddingHorizontal: 8, borderRadius: 4 },
  pickerActions: { gap: 8, marginTop: 12 },
  pickerButton: { flex: 1, minHeight: 34, borderRadius: 6, alignItems: 'center', justifyContent: 'center' },
  table: { borderWidth: 1, borderRadius: 10, overflow: 'hidden' },
  row: { flexDirection: 'row' },
  cell: { flex: 1, justifyContent: 'center' },
  tableHead: { paddingVertical: 10, borderBottomWidth: 1 },
  tableRow: { paddingVertical: 8 },
  headText: { textAlign: 'center', fontSize: 12, fontWeight: '600', includeFontPadding: false },
  subtext: { textAlign: 'center', fontSize: 9, marginTop: 4, includeFontPadding: false },
  count: { textAlign: 'center', fontSize: 16, fontWeight: '700', includeFontPadding: false },
  unit: { fontSize: 10, fontWeight: '400' },
  missing: { textAlign: 'center', fontSize: 10, includeFontPadding: false },
  note: { fontSize: 10, lineHeight: 17, marginTop: 10 },
});
