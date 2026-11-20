import AsyncStorage from '@react-native-async-storage/async-storage';
import { StatusBar } from 'expo-status-bar';
import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Image, Platform, Pressable, ScrollView, StyleSheet, Text, useWindowDimensions } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { expo } from '../../../app.json';
import { RIDER_THEME_KEY, riderThemes } from '../../screens/home/ui/theme';
import { loadMaintenanceState, type ClientConfigParams } from '../api/clientConfig';
import { onMaintenance, type MaintenanceState } from '../api/maintenanceState';

// Expo's local version source uses these values when generating both native apps.
const configParams = Platform.select<ClientConfigParams>({
  android: { platform: 'android', appVersion: expo.version, buildNumber: expo.android.versionCode },
  ios: { platform: 'ios', appVersion: expo.version, buildNumber: expo.ios.buildNumber },
  default: { platform: 'web' },
});

export function MaintenanceGate({ children }: { children: ReactNode }) {
  const [maintenance, setMaintenance] = useState<MaintenanceState>({ active: false });
  const [checking, setChecking] = useState(false);
  const request = useRef<AbortController | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    request.current = controller;
    const unsubscribe = onMaintenance(setMaintenance);
    void loadMaintenanceState(configParams, controller.signal).then((state) => {
      if (!controller.signal.aborted && state.active) setMaintenance(state);
    });
    return () => {
      request.current?.abort();
      unsubscribe();
    };
  }, []);

  async function retry() {
    request.current?.abort();
    const controller = new AbortController();
    request.current = controller;
    setChecking(true);
    const state = await loadMaintenanceState(configParams, controller.signal);
    if (controller.signal.aborted) return;
    setMaintenance(state);
    setChecking(false);
  }

  return maintenance.active
    ? <MaintenanceScreen message={maintenance.message} checking={checking} onRetry={retry} />
    : <>{children}</>;
}

function MaintenanceScreen({ message, checking, onRetry }: {
  message: string;
  checking: boolean;
  onRetry: () => void;
}) {
  const [dark, setDark] = useState(() => {
    const hour = new Date().getHours();
    return hour < 7 || hour >= 19;
  });
  const { width } = useWindowDimensions();
  const theme = riderThemes[dark ? 'dark' : 'light'];

  useEffect(() => {
    let mounted = true;
    void AsyncStorage.getItem(RIDER_THEME_KEY).then((stored) => {
      if (mounted && (stored === 'light' || stored === 'dark')) setDark(stored === 'dark');
    }).catch(() => {});
    return () => { mounted = false; };
  }, []);

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: dark ? theme.raised : '#F7F8FA' }]}>
      <StatusBar style={dark ? 'light' : 'dark'} />
      <ScrollView contentContainerStyle={styles.content}>
        <Image
          accessible
          accessibilityRole="image"
          accessibilityLabel="따릉라이더"
          source={dark ? require('../../../assets/web-ui/brand-logo-dark.png') : require('../../../assets/web-ui/brand-logo.png')}
          style={{ width: Math.min(220, width * 0.6), aspectRatio: 3 }}
        />
        <Image accessible={false} source={require('../../../assets/web-ui/info.png')} style={[styles.infoIcon, { tintColor: theme.text }]} />
        <Text accessibilityRole="header" style={[styles.heading, { color: theme.text }]}>서비스 점검 중</Text>
        <Text accessibilityLiveRegion="polite" style={[styles.message, { color: theme.muted }]}>{message}</Text>
        <Pressable
          accessibilityRole="button"
          accessibilityState={{ disabled: checking, busy: checking }}
          disabled={checking}
          onPress={onRetry}
          style={[styles.retry, checking && styles.checking]}
        >
          <Image accessible={false} source={require('../../../assets/web-ui/refresh.png')} style={styles.retryIcon} />
          <Text style={styles.retryLabel}>{checking ? '확인하고 있어요…' : '다시 시도'}</Text>
        </Pressable>
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: { flex: 1 },
  content: { flexGrow: 1, alignItems: 'center', justifyContent: 'center', gap: 14, paddingHorizontal: 16, paddingVertical: 24 },
  infoIcon: { width: 34, height: 34 },
  heading: { fontSize: 22, fontWeight: '700', textAlign: 'center', includeFontPadding: false },
  message: { maxWidth: 420, fontSize: 16, lineHeight: 27.2, textAlign: 'center', includeFontPadding: false },
  retry: {
    minHeight: 48, alignItems: 'center', justifyContent: 'center', flexDirection: 'row',
    gap: 6, paddingHorizontal: 8, paddingVertical: 15, borderRadius: 9, backgroundColor: riderThemes.light.accent,
  },
  checking: { opacity: 0.5 },
  retryIcon: { width: 18, height: 18, tintColor: '#FFFFFF' },
  retryLabel: { color: '#FFFFFF', fontSize: 12, fontWeight: '600', includeFontPadding: false },
});
