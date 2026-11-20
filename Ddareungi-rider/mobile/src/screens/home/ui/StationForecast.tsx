import { useState } from 'react';
import { Image, Pressable, StyleSheet, Text, View } from 'react-native';
import { FORECAST_HORIZONS, forecastPath } from '../../../features/forecast/api/forecastApi';
import { useStationForecast } from '../../../features/forecast/model/useStationForecasts';
import { forecastAt } from '../../../shared/rider-contract/status';
import { riderThemes, useRiderTheme } from './theme';

const spark = require('../../../features/forecast/assets/spark.png');
const timeFormat = new Intl.DateTimeFormat('ko-KR', { hour: '2-digit', minute: '2-digit' });

export function StationForecast({ stationId, now = Date.now() }: { stationId: string; now?: number }) {
  const [minutes, setMinutes] = useState<number>(15);
  const theme = useRiderTheme();
  const dark = theme === riderThemes.dark;
  const forecast = useStationForecast(stationId);
  if (!forecastPath) return null;

  const data = forecast.data;
  const prediction = data ? forecastAt(data, minutes, now) : null;
  const badgeColor = dark ? theme.muted : '#778292';
  const predictionColor = dark ? theme.blue : '#4D6280';
  const placeholderColor = dark ? theme.muted : '#929CA9';

  return <View>
    <View style={s.titleRow}>
      <View style={s.title}>
        <Image accessible={false} source={spark} style={[s.titleIcon, { tintColor: theme.text }]} />
        <Text accessibilityRole="header" style={[s.titleText, { color: theme.text }]}>도착할 때는 어떨까요?</Text>
      </View>
      <Text style={[s.badge, { color: badgeColor, backgroundColor: dark ? theme.raised : '#ECF0F5' }]}>AI 예측</Text>
    </View>
    <View accessibilityLabel="도착 예상 시간" style={s.horizons}>
      {FORECAST_HORIZONS.map((value) => {
        const selected = minutes === value;
        const color = selected ? dark ? theme.blue : '#3F5B83' : dark ? theme.muted : '#8892A1';
        return <Pressable key={value} accessibilityRole="button" accessibilityState={{ selected }} hitSlop={6}
          onPress={() => setMinutes(value)} style={[s.horizon, {
            backgroundColor: selected ? (dark ? theme.selected : '#E0E7F1') : (dark ? theme.raised : '#F3F5F8'),
          }]}>
          <Text style={[s.horizonText, { color }, selected && s.selectedHorizon]}>{value}분</Text>
        </Pressable>;
      })}
    </View>
    {prediction && data ? <>
      <View accessibilityLiveRegion="polite" style={[s.result, { backgroundColor: dark ? theme.selected : '#EDF1F7' }]}>
        <Text style={[s.caption, { color: predictionColor }]}>{timeFormat.format(new Date(prediction.targetTime))} 예상 수량</Text>
        <Text style={[s.count, { color: predictionColor }]}>약 {prediction.count}<Text style={s.unit}>대</Text></Text>
        <Text style={[s.caption, { color: predictionColor }]}>기준 {timeFormat.format(new Date(data.baseTime))}</Text>
      </View>
      <Text style={[s.footnote, { color: dark ? theme.muted : '#8E98A5' }]}>예상 수량은 도착 시 대여를 보장하지 않아요.</Text>
    </> : <View accessibilityLiveRegion="polite" style={[s.unavailable, {
      backgroundColor: dark ? theme.raised : '#F7F9FB', borderColor: dark ? theme.field : '#DAE0E8',
    }]}>
      <Image accessible={false} source={spark} style={[s.placeholderIcon, { tintColor: placeholderColor }]} />
      <Text style={[s.unavailableTitle, { color: dark ? theme.muted : '#748092' }]}>
        {forecast.isFetching ? '예상 수량을 확인하고 있어요' : '예측 정보를 준비하고 있어요'}
      </Text>
      <Text style={[s.unavailableText, { color: placeholderColor }]}>
        {minutes}분 후 예상 수량은 준비되는 대로 보여드릴게요.{ '\n' }지금은 현재 대여 수를 확인해 주세요.
      </Text>
    </View>}
  </View>;
}

const s = StyleSheet.create({
  titleRow: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10 },
  title: { flexDirection: 'row', alignItems: 'center', flexShrink: 1, gap: 5 },
  titleIcon: { width: 18, height: 18 },
  titleText: { fontSize: 12, flexShrink: 1 },
  badge: { fontSize: 8, paddingHorizontal: 6, paddingVertical: 4, borderRadius: 5, fontWeight: '500' },
  horizons: { flexDirection: 'row', gap: 4, marginVertical: 16 },
  horizon: { flex: 1, alignItems: 'center', paddingVertical: 8, borderRadius: 6 },
  horizonText: { fontSize: 10 },
  selectedHorizon: { fontWeight: '700' },
  result: { padding: 16, borderRadius: 9 },
  caption: { fontSize: 9 },
  count: { marginVertical: 8, fontSize: 28, fontWeight: '700' },
  unit: { fontSize: 12 },
  footnote: { marginTop: 8, fontSize: 9 },
  unavailable: { alignItems: 'center', paddingVertical: 17, paddingHorizontal: 8, borderWidth: 1, borderStyle: 'dashed', borderRadius: 9 },
  placeholderIcon: { width: 24, height: 24, marginBottom: 8 },
  unavailableTitle: { fontSize: 11, fontWeight: '500', textAlign: 'center' },
  unavailableText: { marginTop: 8, fontSize: 9, lineHeight: 16, textAlign: 'center' },
});
