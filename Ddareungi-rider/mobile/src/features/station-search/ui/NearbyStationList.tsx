import { FlatList, StyleSheet, Text, View } from 'react-native';
import { Station } from '../../../entities/station/model/types';

export function NearbyStationList({ stations }: { stations: Station[] }) {
  return (
    <FlatList
      data={stations}
      keyExtractor={(item) => item.stationId}
      contentContainerStyle={styles.list}
      renderItem={({ item }) => (
        <View style={styles.card}>
          <View style={styles.row}>
            <Text style={styles.name}>{item.stationName}</Text>
            <Text style={styles.count}>{item.availableBikeCount}대</Text>
          </View>
          <Text style={styles.address}>{item.address}</Text>
          <Text style={styles.meta}>{Math.round(item.distanceMeters)}m · 거치대 {item.rackTotalCount}개</Text>
        </View>
      )}
    />
  );
}

const styles = StyleSheet.create({
  list: { gap: 10, paddingVertical: 16 },
  card: { padding: 16, borderRadius: 14, backgroundColor: 'white' },
  row: { flexDirection: 'row', justifyContent: 'space-between', gap: 12 },
  name: { flex: 1, color: '#13231b', fontSize: 16, fontWeight: '700' },
  count: { color: '#058a4b', fontSize: 17, fontWeight: '800' },
  address: { marginTop: 7, color: '#405249', fontSize: 13 },
  meta: { marginTop: 8, color: '#64746c' },
});
