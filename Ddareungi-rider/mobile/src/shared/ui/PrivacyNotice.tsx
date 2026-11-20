import { Modal, ScrollView, StyleSheet, Text, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { ActionButton } from './ActionButton';

export function PrivacyNotice({ onClose }: { onClose: () => void }) {
  return (
    <Modal animationType="slide" onRequestClose={onClose}>
      <SafeAreaView style={styles.page}>
        <View style={styles.toolbar}>
          <Text accessibilityRole="header" style={styles.title}>개인정보 처리 안내</Text>
          <ActionButton label="닫기" onPress={onClose} />
        </View>
        <ScrollView contentContainerStyle={styles.content}>
          <Text accessibilityRole="header" style={styles.heading}>위치와 검색어</Text>
          <Text style={styles.body}>
            위치 기능을 선택하면 앱 사용 중 위치 권한을 요청합니다. 주변 대여소를 찾거나 길찾기를 할 때 현재 위치 또는
            선택한 좌표를 따릉라이더 서버로 보냅니다. 장소와 대여소를 검색할 때는 검색어와 검색 기준 좌표를 서버로 보냅니다.
            위치 권한 없이도 장소 검색과 지도를 이용할 수 있습니다.
          </Text>
          <Text accessibilityRole="header" style={styles.heading}>카카오 지도와 길찾기</Text>
          <Text style={styles.body}>
            앱의 카카오 지도 SDK는 지도를 표시하는 데 필요한 데이터를 카카오에 요청합니다. 서버는 카카오 서비스로
            장소를 검색하거나 경로를 계산할 때 필요한 검색어와 좌표를 카카오에 보냅니다.
          </Text>
          <Text accessibilityRole="header" style={styles.heading}>기기에 저장되는 항목</Text>
          <Text style={styles.body}>
            즐겨찾기 대여소 ID와 저장 시각, 최근 검색어와 사용 시각, 마지막으로 본 지도의 좌표와 표시 이름은 기기 저장소에
            보관됩니다. 현재 위치 기능으로 지도를 이동한 경우 그 위치가 다음 실행의 지도 중심으로 사용될 수 있습니다.
            대여소 상세에서 즐겨찾기를 해제하고, 주변 대여소 화면의 최근 장소에서 검색어를 지울 수 있습니다.
          </Text>
        </ScrollView>
      </SafeAreaView>
    </Modal>
  );
}

const styles = StyleSheet.create({
  page: { flex: 1, backgroundColor: '#F4FAF7', paddingHorizontal: 16 },
  toolbar: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', gap: 10, marginVertical: 10 },
  title: { flexShrink: 1, color: '#10251B', fontSize: 22, fontWeight: '800' },
  content: { gap: 12, paddingBottom: 18 },
  heading: { color: '#10251B', fontSize: 16, fontWeight: '700' },
  body: { color: '#405249', fontSize: 15, lineHeight: 23 },
});
