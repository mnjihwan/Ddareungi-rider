import { Pressable, ScrollView, StyleSheet, Text, TextInput, View, useWindowDimensions } from 'react-native';
import { matchingParts, type SearchSuggestion } from '../../../shared/rider-contract/searchSuggestions';
import { searchMatchLabel } from '../../../shared/rider-contract/koreanSearch';
import { displayStationName } from '../../../shared/rider-contract/stationName';
import type { useSearchSuggestions } from '../../../features/station-search/model/useSearchSuggestions';
import type { Place } from '../../../shared/rider-contract/types';
import { RiderIcon } from './MapChrome';
import { useRiderTheme } from './theme';

export function SearchField({ text, label, onChange, onFocus, onSubmit, onClear, route, active }: {
  text: string; label: string; onChange: (value: string) => void; onFocus?: () => void;
  onSubmit: () => void; onClear?: () => void; route?: boolean; active?: boolean;
}) {
  const theme = useRiderTheme();
  return (
    <View style={[styles.field, route ? styles.routeField : styles.floatingField, {
      backgroundColor: route ? theme.raised : theme.surface,
      borderColor: active ? '#8DA7CE' : theme.field,
    }]}>
      <RiderIcon name={route ? 'pin' : 'search'} size={route ? 18 : 20} color={route ? theme.text : '#727A86'} />
      <TextInput
        accessibilityLabel={label}
        value={text}
        placeholder={label}
        placeholderTextColor={theme.nav}
        onChangeText={onChange}
        onFocus={onFocus}
        onSubmitEditing={onSubmit}
        autoCapitalize="none"
        autoCorrect={false}
        returnKeyType="search"
        maxLength={100}
        style={[styles.input, { color: theme.text, fontSize: route ? 13 : 12 }]}
      />
      {!!text && onClear && <Pressable hitSlop={8} accessibilityRole="button" accessibilityLabel="검색어 지우기"
        onPress={onClear} style={styles.fieldButton}><RiderIcon name="close" size={16} color={theme.accent} /></Pressable>}
      <Pressable hitSlop={8} accessibilityRole="button" accessibilityLabel="검색" onPress={onSubmit} style={styles.fieldButton}>
        <RiderIcon name={route ? 'search' : 'arrow'} size={18} color={theme.accent} />
      </Pressable>
    </View>
  );
}

export function SearchResults({ query, onChoose, disabled, floating }: {
  query: ReturnType<typeof useSearchSuggestions>;
  onChoose: (result: SearchSuggestion) => void;
  disabled?: (result: SearchSuggestion) => boolean;
  floating?: boolean;
}) {
  const theme = useRiderTheme();
  const { places, stations } = query;
  const { height } = useWindowDimensions();
  const failed = places.isError && stations.isError;
  const placesUnavailable = query.enabled && !failed && places.isError;
  const stationsUnavailable = query.enabled && !failed && stations.isError;
  return (
    <ScrollView
      nestedScrollEnabled keyboardShouldPersistTaps="handled"
      style={[styles.results, floating && { elevation: 3, maxHeight: Math.min(440, height * 0.45) },
        { backgroundColor: theme.surface, borderColor: theme.border }]}
    >
      {query.pending && <Text accessibilityLiveRegion="polite" style={[styles.notice, { color: theme.muted }]}>장소를 찾고 있어요…</Text>}
      {query.enabled && !query.pending && failed && <Pressable accessibilityRole="button" onPress={() => {
        void places.refetch(); void stations.refetch();
      }}>
        <Text accessibilityLiveRegion="polite" style={[styles.notice, { color: theme.muted }]}>
          검색에 연결하지 못했어요. 다시 입력하거나 검색해 주세요.
        </Text>
      </Pressable>}
      {query.enabled && !query.pending && !query.results.length && !failed &&
        <Text style={[styles.notice, { color: theme.muted }]}>일치하는 장소가 없어요. 다른 이름으로 검색해 주세요.</Text>}
      {query.results.map((item, index) => {
        const unavailable = disabled?.(item) ?? false;
        const kind = item.kind === 'station' ? '대여소' : item.place.categoryGroupCode === 'SW8' ? '지하철역' : '장소';
        const address = [searchMatchLabel(item.name, item.address, query.term), item.address].filter(Boolean).join(' · ');
        return (
          <Pressable accessibilityRole="button" key={item.key} onPress={() => onChoose(item)} disabled={unavailable}
            style={[styles.result, { borderColor: theme.border, borderTopWidth: index ? 1 : 0, opacity: unavailable ? 0.5 : 1 }]}>
            <RiderIcon name={item.kind === 'station' ? 'bike' : 'search'} size={17} color={theme.muted} />
            <View style={styles.resultText}>
              <Text style={{ color: theme.text, fontWeight: '500', fontSize: 13, lineHeight: 19.5 }}>
                <SearchMatchText name={item.kind === 'station' ? displayStationName(item.name) : item.name} term={query.term} />
              </Text>
              {!!address && <Text numberOfLines={1} style={{ color: theme.muted, fontSize: 11, lineHeight: 16.5 }}>{address}</Text>}
            </View>
            <Text style={[styles.kind, { color: theme.muted, backgroundColor: theme.raised }]}>{kind}</Text>
          </Pressable>
        );
      })}
      {!query.pending && (placesUnavailable || stationsUnavailable) && <Pressable accessibilityRole="button"
        onPress={() => void (placesUnavailable ? places.refetch() : stations.refetch())}>
        <Text style={[styles.notice, { color: theme.warning }]}>
          {placesUnavailable ? '장소 연결을 확인하지 못해 대여소만 표시해요.' : '대여소 연결을 확인하지 못해 장소만 표시해요.'}
        </Text>
      </Pressable>}
    </ScrollView>
  );
}

export function SearchMatchText({ name, term }: { name: string; term: string }) {
  const theme = useRiderTheme();
  return <>{matchingParts(name, term).map((part, index) =>
    <Text key={index} style={part.matched ? { color: theme.accent, fontWeight: '800' } : undefined}>{part.text}</Text>
  )}</>;
}

export function SubmittedPlaceRow({ place, term, onPress }: { place: Place; term: string; onPress: () => void }) {
  const theme = useRiderTheme();
  const details = [searchMatchLabel(place.name, place.address, term), place.address].filter(Boolean).join(' · ');
  return (
    <Pressable accessibilityRole="button" onPress={onPress} style={[styles.submittedPlace, { borderColor: theme.border }]}>
      <View style={[styles.placeIcon, { backgroundColor: theme.raised }]}>
        <RiderIcon name="pin" color={theme.blue} size={18} />
      </View>
      <View style={styles.resultText}>
        <Text style={{ color: theme.text, fontSize: 12, fontWeight: '700' }}><SearchMatchText name={place.name} term={term} /></Text>
        {!!details && <Text style={{ color: theme.muted, fontSize: 10, marginTop: 5 }}>{details}</Text>}
      </View>
      <RiderIcon name="arrow" size={17} color={theme.text} />
    </Pressable>
  );
}

const styles = StyleSheet.create({
  field: { flexDirection: 'row', alignItems: 'center', gap: 10, borderRadius: 10, paddingHorizontal: 13 },
  floatingField: { height: 49, gap: 12, elevation: 3, shadowColor: '#364253', shadowOpacity: 0.12, shadowRadius: 12, shadowOffset: { width: 0, height: 4 } },
  routeField: { minHeight: 50, borderWidth: 1, gap: 8, paddingHorizontal: 10 },
  input: { flex: 1, alignSelf: 'stretch', paddingVertical: 0, paddingHorizontal: 0, includeFontPadding: false },
  fieldButton: { minWidth: 32, minHeight: 44, justifyContent: 'center', alignItems: 'center' },
  results: { borderRadius: 12, borderWidth: 1, marginTop: 6, maxHeight: 240 },
  notice: { padding: 12, fontSize: 12 },
  result: { minHeight: 56, padding: 12, flexDirection: 'row', alignItems: 'center', gap: 10 },
  resultText: { flex: 1, minWidth: 0 },
  kind: { fontSize: 10, paddingHorizontal: 5, paddingVertical: 3, borderRadius: 4 },
  submittedPlace: { flexDirection: 'row', alignItems: 'center', gap: 9, paddingVertical: 14, paddingHorizontal: 4, borderBottomWidth: 1 },
  placeIcon: { borderRadius: 8, padding: 9 },
});
