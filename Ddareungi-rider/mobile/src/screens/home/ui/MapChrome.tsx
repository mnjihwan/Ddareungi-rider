import { type ReactNode, useMemo, useRef } from 'react';
import { Image, PanResponder, Pressable, StyleSheet, Text, View, useWindowDimensions } from 'react-native';
import { HEADER_HEIGHT, type SheetLevel } from './geometry';
import { useRiderTheme } from './theme';

export type ViewMode = 'nearby' | 'route' | 'favorites';

const MODES: { key: ViewMode; label: string; compactWidth: number }[] = [
  { key: 'nearby', label: '주변 대여소', compactWidth: 66 },
  { key: 'route', label: '길찾기', compactWidth: 41 },
  { key: 'favorites', label: '즐겨찾기', compactWidth: 70 },
];

export type RiderIconName = 'search' | 'arrow' | 'moon' | 'sun' | 'locate' | 'plus' | 'minus'
  | 'refresh' | 'chevron' | 'star' | 'star-saved' | 'close' | 'bike' | 'pin' | 'clock' | 'walk' | 'info';

const icons: Record<RiderIconName, number> = {
  search: require('../../../../assets/web-ui/search.png'),
  arrow: require('../../../../assets/web-ui/arrow.png'),
  moon: require('../../../../assets/web-ui/moon.png'),
  sun: require('../../../../assets/web-ui/sun.png'),
  locate: require('../../../../assets/web-ui/locate.png'),
  plus: require('../../../../assets/web-ui/plus.png'),
  minus: require('../../../../assets/web-ui/minus.png'),
  refresh: require('../../../../assets/web-ui/refresh.png'),
  chevron: require('../../../../assets/web-ui/chevron.png'),
  star: require('../../../../assets/web-ui/star.png'),
  'star-saved': require('../../../../assets/web-ui/star-saved.png'),
  close: require('../../../../assets/web-ui/close.png'),
  bike: require('../../../../assets/web-ui/bike.png'),
  pin: require('../../../../assets/web-ui/pin.png'),
  clock: require('../../../../assets/web-ui/clock.png'),
  walk: require('../../../../assets/web-ui/walk.png'),
  info: require('../../../../assets/web-ui/info.png'),
};

export function RiderIcon({ name, color, size = 20 }: { name: RiderIconName; color?: string; size?: number }) {
  const theme = useRiderTheme();
  const saved = name === 'star-saved';
  const source = saved && theme.surface !== '#FFFFFF' ? require('../../../../assets/web-ui/star-saved-dark.png') : icons[name];
  return <Image accessible={false} source={source} style={{ width: size, height: size, tintColor: saved ? undefined : color ?? theme.text }} />;
}

export function MapHeader({ view, onView, favorites, dark, onTheme }: {
  view: ViewMode; onView: (view: ViewMode) => void; favorites: number; dark: boolean; onTheme: () => void;
}) {
  const theme = useRiderTheme();
  const compact = useWindowDimensions().width <= 380;
  return (
    <View style={[styles.header, { backgroundColor: theme.surface, borderBottomColor: theme.border }]}>
      <Pressable accessibilityRole="button" accessibilityLabel="따릉라이더 홈" onPress={() => onView('nearby')}>
        <Image source={dark ? require('../../../../assets/web-ui/brand-symbol-dark.png') : require('../../../../assets/web-ui/brand-symbol.png')}
          style={styles.logo} resizeMode="contain" />
      </Pressable>
      <View style={[styles.tabs, { gap: compact ? 2 : 4 }]} accessibilityRole="tablist">
        {MODES.map(({ key, label, compactWidth }) => (
          <Pressable key={key} onPress={() => onView(key)} accessibilityRole="tab"
            accessibilityState={{ selected: view === key }}
            style={[styles.tab, { minWidth: compact ? compactWidth : undefined, paddingHorizontal: compact ? 4 : 8,
              backgroundColor: view === key ? theme.selected : 'transparent' }]}>
            <Text numberOfLines={1} style={[styles.tabLabel, { fontSize: compact ? 11 : 12, color: view === key ? theme.accent : theme.nav }]}>{label}</Text>
            {key === 'favorites' && <Text style={[styles.navCount, { color: view === key ? theme.accent : theme.nav, backgroundColor: theme.handle }]}>{favorites}</Text>}
          </Pressable>
        ))}
      </View>
      <Pressable accessibilityRole="switch" accessibilityLabel="다크 모드" accessibilityState={{ checked: dark }}
        onPress={onTheme} style={styles.themeButton}>
        <View style={[styles.themeIcon, { backgroundColor: theme.surfaceChip }]}>
          <RiderIcon name={dark ? 'sun' : 'moon'} color={theme.blue} size={21} />
        </View>
      </Pressable>
    </View>
  );
}

export function MapButtons({ bottom, locate, zoom, locating }: {
  bottom: number;
  locate: () => void;
  zoom: (change: number) => void;
  locating: boolean;
}) {
  const theme = useRiderTheme();
  return (
    <View pointerEvents="box-none" style={[styles.mapButtons, { bottom }]}>
      <Pressable accessibilityRole="button" accessibilityLabel="현재 위치" accessibilityState={{ busy: locating }}
        onPress={locate} disabled={locating}
        style={[styles.mapButton, styles.location, { backgroundColor: theme.surface, opacity: locating ? 0.55 : 1 }]}>
        <RiderIcon name="locate" />
      </Pressable>
      <View style={[styles.zoomGroup, { backgroundColor: theme.surface }]}>
        <Pressable style={styles.mapButton} accessibilityRole="button" accessibilityLabel="지도 확대" onPress={() => zoom(1)}>
          <RiderIcon name="plus" size={20} />
        </Pressable>
        <View style={{ height: StyleSheet.hairlineWidth, backgroundColor: theme.border }} />
        <Pressable style={styles.mapButton} accessibilityRole="button" accessibilityLabel="지도 축소" onPress={() => zoom(-1)}>
          <RiderIcon name="minus" size={20} />
        </Pressable>
      </View>
    </View>
  );
}

export function BottomSheet({ level, heights, dragHeight, onDrag, onLevel, children }: {
  level: SheetLevel; heights: Record<SheetLevel, number>; dragHeight?: number;
  onDrag: (height?: number) => void; onLevel: (level: SheetLevel) => void; children: ReactNode;
}) {
  const theme = useRiderTheme();
  const controls = useRef({ level, heights, onDrag, onLevel });
  controls.current = { level, heights, onDrag, onLevel };
  const pan = useMemo(() => {
    function finish(dy: number) {
      const { level, onDrag, onLevel } = controls.current;
      onDrag(undefined);
      if (Math.abs(dy) <= 30) return;
      onLevel(dy < 0 ? level === 'peek' ? 'mid' : 'full' : level === 'full' ? 'mid' : 'peek');
    }
    return PanResponder.create({
      onStartShouldSetPanResponder: () => true,
      onPanResponderMove: (_, { dy }) => {
        const { level, heights, onDrag } = controls.current;
        onDrag(Math.max(heights.peek, Math.min(heights.full, heights[level] - dy)));
      },
      onPanResponderRelease: (_, { dy }) => finish(dy),
      onPanResponderTerminate: () => controls.current.onDrag(undefined),
    });
  }, []);

  const height = dragHeight ?? heights[level];
  const value = level === 'peek' ? 0 : level === 'mid' ? 1 : 2;
  return (
    <View style={[styles.sheet, { height, backgroundColor: theme.surface }]}>
      <View {...pan.panHandlers} style={[styles.handleSurface, level === 'peek' && dragHeight === undefined && styles.peekHandle]}
        accessible accessibilityRole="adjustable" accessibilityLabel="하단 패널 크기"
        accessibilityValue={{ min: 0, max: 2, now: value, text: ['접힘', '중간', '펼쳐짐'][value] }}
        accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }, { name: 'activate' }]}
        onAccessibilityAction={({ nativeEvent }) => {
          const { actionName } = nativeEvent;
          if (actionName === 'increment') onLevel(level === 'peek' ? 'mid' : 'full');
          if (actionName === 'decrement') onLevel(level === 'full' ? 'mid' : 'peek');
          if (actionName === 'activate') onLevel(level === 'peek' ? 'mid' : level === 'mid' ? 'full' : 'mid');
        }}>
        <View style={[styles.handle, { backgroundColor: theme.handle }]} />
      </View>
      {height > 48 && children}
    </View>
  );
}

const styles = StyleSheet.create({
  header: { zIndex: 2, height: HEADER_HEIGHT, paddingHorizontal: 12, gap: 4, flexDirection: 'row', alignItems: 'center', borderBottomWidth: 1 },
  logo: { width: 44, height: 44 },
  tabs: { marginLeft: 'auto', flexDirection: 'row', alignItems: 'center' },
  tab: { minHeight: 44, borderRadius: 9, flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 4 },
  tabLabel: { fontWeight: '600', includeFontPadding: false },
  navCount: { paddingHorizontal: 5, paddingVertical: 2, borderRadius: 5, fontSize: 8, includeFontPadding: false, textAlign: 'center' },
  themeButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  themeIcon: { width: 36, height: 36, borderRadius: 18, alignItems: 'center', justifyContent: 'center' },
  mapButtons: { position: 'absolute', right: 16, alignItems: 'center', gap: 12, zIndex: 2 },
  mapButton: { width: 44, height: 44, alignItems: 'center', justifyContent: 'center' },
  location: { borderRadius: 10, elevation: 2, shadowColor: '#364253', shadowOpacity: 0.10, shadowRadius: 8, shadowOffset: { width: 0, height: 2 } },
  zoomGroup: { borderRadius: 10, elevation: 2, shadowColor: '#364253', shadowOpacity: 0.10, shadowRadius: 8, shadowOffset: { width: 0, height: 2 } },
  sheet: {
    position: 'absolute', left: 0, right: 0, bottom: 0, zIndex: 4, elevation: 5,
    borderTopLeftRadius: 22, borderTopRightRadius: 22, overflow: 'hidden',
    shadowColor: '#293648', shadowOpacity: 0.10, shadowRadius: 12, shadowOffset: { width: 0, height: -4 },
  },
  handleSurface: { height: 37, alignItems: 'center', justifyContent: 'center' },
  peekHandle: { height: '100%' },
  handle: { width: 34, height: 4, borderRadius: 10 },
});
