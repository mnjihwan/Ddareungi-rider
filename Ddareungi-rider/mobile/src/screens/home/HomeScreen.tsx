import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { useNearbyStations } from '../../features/station-search/model/useNearbyStations';
import { NearbyStationList } from '../../features/station-search/ui/NearbyStationList';

const INITIAL_LOCATION = { latitude: 37.4979, longitude: 127.0276 };

export function HomeScreen() {
  const query = useNearbyStations(INITIAL_LOCATION.latitude, INITIAL_LOCATION.longitude);

  return (
    <View style={styles.container}>
      <Text style={styles.eyebrow}>DDareungi Rider</Text>
      <Text style={styles.title}>가까운 따릉이를{`\n`}빠르게 찾아보세요</Text>
      <View style={styles.mapPlaceholder}>
        <Text style={styles.mapText}>지도 PoC 영역</Text>
        <Text style={styles.mapHint}>네이버 지도 SDK 연동 전 임시 화면</Text>
      </View>
      <Text style={styles.sectionTitle}>강남역 주변 대여소</Text>
      {query.isPending && <ActivityIndicator color="#058a4b" />}
      {query.isError && <Text style={styles.error}>서버에 연결하지 못했습니다.</Text>}
      {query.data && <NearbyStationList stations={query.data} />}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1, paddingHorizontal: 20, paddingTop: 22 },
  eyebrow: { color: '#058a4b', fontSize: 13, fontWeight: '800', letterSpacing: 1.2 },
  title: { marginTop: 8, color: '#10251b', fontSize: 30, fontWeight: '800', lineHeight: 38 },
  mapPlaceholder: { height: 190, marginTop: 22, borderRadius: 20, backgroundColor: '#caead8', alignItems: 'center', justifyContent: 'center' },
  mapText: { color: '#12613b', fontSize: 18, fontWeight: '700' },
  mapHint: { marginTop: 5, color: '#41705a', fontSize: 12 },
  sectionTitle: { marginTop: 24, color: '#13231b', fontSize: 19, fontWeight: '800' },
  error: { marginTop: 18, color: '#b42318' },
});
